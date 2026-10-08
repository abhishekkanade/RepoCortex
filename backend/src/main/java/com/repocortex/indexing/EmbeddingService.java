package com.repocortex.indexing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    // must match vector(1536) in the schema
    static final int DIMENSIONS = 1536;
    static final int MAX_ATTEMPTS = 3;
    private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(60);

    private final EmbeddingModel embeddingModel;
    private final NamedParameterJdbcTemplate jdbc;
    private final TokenRateLimiter rateLimiter;
    // wait before retry n is initialBackoff * 3^(n-1): 10s, then 30s (limits are per minute)
    private final Duration initialBackoff;

    @Autowired
    public EmbeddingService(EmbeddingModel embeddingModel, NamedParameterJdbcTemplate jdbc,
                            @Value("${app.embedding.tokens-per-minute}") int tokensPerMinute) {
        this(embeddingModel, jdbc, tokensPerMinute, Duration.ofSeconds(10));
    }

    EmbeddingService(EmbeddingModel embeddingModel, NamedParameterJdbcTemplate jdbc,
                     int tokensPerMinute, Duration initialBackoff) {
        this.embeddingModel = embeddingModel;
        this.jdbc = jdbc;
        this.rateLimiter = new TokenRateLimiter(tokensPerMinute);
        this.initialBackoff = initialBackoff;
    }

    // Makes sure every chunk has an embedding in embedding_cache. Only cache misses go to the provider.
    // Returns how many chunks were actually embedded.
    public int ensureCached(List<Chunk> chunks) {
        Map<String, String> textByHash = new LinkedHashMap<>();
        for (Chunk chunk : chunks) {
            textByHash.putIfAbsent(chunk.contentHash(), chunk.content());
        }

        Set<String> cached = new HashSet<>(jdbc.queryForList(
                "SELECT content_hash FROM embedding_cache WHERE content_hash IN (:hashes)",
                Map.of("hashes", textByHash.keySet()),
                String.class));

        List<String> missingHashes = new ArrayList<>();
        List<String> missingTexts = new ArrayList<>();
        textByHash.forEach((hash, text) -> {
            if (!cached.contains(hash)) {
                missingHashes.add(hash);
                missingTexts.add(text);
            }
        });
        if (missingTexts.isEmpty()) {
            return 0;
        }

        List<float[]> vectors = embedAll(missingTexts);
        if (vectors.size() != missingTexts.size()) {
            throw new IllegalStateException("Embedding API returned " + vectors.size() + " vectors for " + missingTexts.size() + " texts");
        }
        if (vectors.get(0).length != DIMENSIONS) {
            throw new IllegalStateException("Embedding model returned " + vectors.get(0).length
                    + " dimensions, the database expects " + DIMENSIONS);
        }

        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < missingHashes.size(); i++) {
            rows.add(new Object[]{missingHashes.get(i), toVectorLiteral(vectors.get(i))});
        }
        jdbc.getJdbcOperations().batchUpdate(
                "INSERT INTO embedding_cache (content_hash, embedding) VALUES (?, ?::vector) ON CONFLICT (content_hash) DO NOTHING",
                rows);
        return missingTexts.size();
    }

    // With a token budget, one request may use at most half of it, so two batches in flight both fit.
    public int maxRequestTokens() {
        return rateLimiter.isLimited() ? rateLimiter.tokensPerMinute() / 2 : Integer.MAX_VALUE;
    }

    List<float[]> embedAll(List<String> texts) {
        int maxRequestTokens = maxRequestTokens();
        List<float[]> vectors = new ArrayList<>(texts.size());
        List<String> request = new ArrayList<>();
        int requestTokens = 0;
        for (String text : texts) {
            int tokens = TokenRateLimiter.estimateTokens(text);
            if (!request.isEmpty() && requestTokens + tokens > maxRequestTokens) {
                vectors.addAll(embedWithRetry(request, requestTokens));
                request = new ArrayList<>();
                requestTokens = 0;
            }
            request.add(text);
            requestTokens += tokens;
        }
        vectors.addAll(embedWithRetry(request, requestTokens));
        return vectors;
    }

    // Retries only rate limits (HTTP 429); any other error fails the batch right away.
    List<float[]> embedWithRetry(List<String> texts, int estimatedTokens) {
        for (int attempt = 1; ; attempt++) {
            try {
                rateLimiter.acquire(estimatedTokens);
                return embeddingModel.embed(texts);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the embedding rate limit", e);
            } catch (RuntimeException e) {
                EmbeddingRateLimitException rateLimit = findRateLimit(e);
                if (rateLimit == null) {
                    throw e;
                }
                if (attempt >= MAX_ATTEMPTS) {
                    throw new IllegalStateException("Embedding provider rate limit (429) still hit after "
                            + MAX_ATTEMPTS + " attempts, please try again in a few minutes", e);
                }
                Duration wait = waitBeforeRetry(rateLimit, attempt);
                log.info("Embedding rate limited (attempt {}/{}), retrying in {} s", attempt, MAX_ATTEMPTS, wait.toSeconds());
                sleep(wait);
            }
        }
    }

    private Duration waitBeforeRetry(EmbeddingRateLimitException rateLimit, int attempt) {
        // use the provider's Retry-After when it gives a sane value
        Duration retryAfter = rateLimit.retryAfter();
        if (retryAfter != null && retryAfter.compareTo(MAX_RETRY_AFTER) <= 0) {
            return retryAfter;
        }
        return initialBackoff.multipliedBy((long) Math.pow(3, attempt - 1));
    }

    // the exception may come back wrapped, so look through the causes
    private static EmbeddingRateLimitException findRateLimit(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof EmbeddingRateLimitException rateLimit) {
                return rateLimit;
            }
        }
        return null;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry", e);
        }
    }

    // pgvector accepts the text form "[0.1,0.2,...]"
    static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 12).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
