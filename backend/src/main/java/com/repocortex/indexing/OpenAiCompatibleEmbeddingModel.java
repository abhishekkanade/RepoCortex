package com.repocortex.indexing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// Calls POST {baseUrl}/embeddings on any OpenAI-compatible provider (OpenAI, Gemini, ...).
// Spring AI's OpenAI client can't be used for Gemini: Gemini leaves out "index": 0 and the client rejects that.
public class OpenAiCompatibleEmbeddingModel implements EmbeddingModel {

    private final RestClient restClient;
    private final String model;
    private final int dimensions;

    public OpenAiCompatibleEmbeddingModel(RestClient.Builder builder, String baseUrl, String apiKey,
                                          String model, int dimensions) {
        this.restClient = builder
                .baseUrl(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
        this.model = model;
        this.dimensions = dimensions;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        ResponseJson json = restClient.post()
                .uri("/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RequestJson(model, request.getInstructions(), dimensions > 0 ? dimensions : null))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> handleError(res))
                .body(ResponseJson.class);
        if (json == null || json.data() == null) {
            throw new IllegalStateException("Embedding provider returned an empty response");
        }

        List<Embedding> embeddings = new ArrayList<>();
        for (ItemJson item : json.data()) {
            // a missing index means 0 (Gemini omits default values)
            embeddings.add(new Embedding(item.embedding(), item.index() == null ? 0 : item.index()));
        }
        embeddings.sort(Comparator.comparing(Embedding::getIndex));
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    private static void handleError(ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        if (status == 429) {
            throw new EmbeddingRateLimitException(parseRetryAfter(response.getHeaders().getFirst("retry-after")));
        }
        String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        throw new IllegalStateException("Embedding request failed with HTTP " + status + ": "
                + (body.length() > 300 ? body.substring(0, 300) : body));
    }

    private static Duration parseRetryAfter(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            // HTTP-date form is not worth handling here
            return null;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record RequestJson(String model, List<String> input, Integer dimensions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ResponseJson(List<ItemJson> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ItemJson(Integer index, float[] embedding) {
    }
}
