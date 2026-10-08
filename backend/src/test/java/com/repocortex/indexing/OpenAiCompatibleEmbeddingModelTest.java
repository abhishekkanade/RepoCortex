package com.repocortex.indexing;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiCompatibleEmbeddingModelTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final OpenAiCompatibleEmbeddingModel model = new OpenAiCompatibleEmbeddingModel(
            builder, "https://example.test/v1beta/openai/", "key-123", "gemini-embedding-001", 3);

    @Test
    void handlesGeminiResponseWithoutIndexOnFirstItem() {
        server.expect(requestTo("https://example.test/v1beta/openai/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer key-123"))
                .andExpect(jsonPath("$.model").value("gemini-embedding-001"))
                .andExpect(jsonPath("$.dimensions").value(3))
                .andExpect(jsonPath("$.input[1]").value("b"))
                // same shape Gemini returns: "index": 0 is left out
                .andRespond(withSuccess("""
                        {"object":"list","model":"gemini-embedding-001","data":[
                          {"object":"embedding","index":1,"embedding":[0.4,0.5,0.6]},
                          {"object":"embedding","embedding":[0.1,0.2,0.3]}
                        ]}""", MediaType.APPLICATION_JSON));

        List<float[]> vectors = model.embed(List.of("a", "b"));

        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f}, vectors.get(0));
        assertArrayEquals(new float[]{0.4f, 0.5f, 0.6f}, vectors.get(1));
        server.verify();
    }

    @Test
    void mapsTooManyRequestsToRateLimitException() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("retry-after", "7");
        server.expect(requestTo("https://example.test/v1beta/openai/embeddings"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers));

        EmbeddingRateLimitException e = assertThrows(EmbeddingRateLimitException.class,
                () -> model.embed(List.of("a")));

        assertEquals(Duration.ofSeconds(7), e.retryAfter());
    }

    @Test
    void otherErrorsIncludeStatusAndBody() {
        server.expect(requestTo("https://example.test/v1beta/openai/embeddings"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"error\":\"bad model\"}"));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> model.embed(List.of("a")));

        assertEquals("Embedding request failed with HTTP 400: {\"error\":\"bad model\"}", e.getMessage());
    }
}
