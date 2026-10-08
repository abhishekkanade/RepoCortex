package com.repocortex.indexing;

import java.time.Duration;

// HTTP 429 from the embedding provider; retryAfter is null when the provider didn't say
public class EmbeddingRateLimitException extends RuntimeException {

    private final Duration retryAfter;

    public EmbeddingRateLimitException(Duration retryAfter) {
        super("Embedding provider returned 429 (rate limit)");
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
