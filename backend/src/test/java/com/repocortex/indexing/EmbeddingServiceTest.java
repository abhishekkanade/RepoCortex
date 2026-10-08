package com.repocortex.indexing;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingServiceTest {

    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    private final EmbeddingService service = new EmbeddingService(
            embeddingModel, mock(NamedParameterJdbcTemplate.class), 0, Duration.ofMillis(1));

    @Test
    void retriesRateLimitAndThenSucceeds() {
        List<float[]> vectors = List.of(new float[EmbeddingService.DIMENSIONS]);
        when(embeddingModel.embed(anyList()))
                .thenThrow(rateLimit())
                .thenThrow(new RuntimeException("wrapped", rateLimit()))
                .thenReturn(vectors);

        assertSame(vectors, service.embedWithRetry(List.of("text"), 1));
        verify(embeddingModel, times(3)).embed(anyList());
    }

    @Test
    void givesUpAfterThreeRateLimitedAttempts() {
        when(embeddingModel.embed(anyList())).thenThrow(rateLimit());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.embedWithRetry(List.of("text"), 1));

        assertTrue(e.getMessage().contains("rate limit (429)"));
        verify(embeddingModel, times(EmbeddingService.MAX_ATTEMPTS)).embed(anyList());
    }

    @Test
    void otherErrorsAreNotRetried() {
        when(embeddingModel.embed(anyList())).thenThrow(new IllegalArgumentException("bad request"));

        assertThrows(IllegalArgumentException.class, () -> service.embedWithRetry(List.of("text"), 1));
        verify(embeddingModel, times(1)).embed(anyList());
    }

    @Test
    void splitsRequestsToHalfTheTokenBudget() {
        // 3000 tokens/min -> at most 1500 tokens per request; each text is ~500 tokens
        EmbeddingService limited = new EmbeddingService(
                embeddingModel, mock(NamedParameterJdbcTemplate.class), 3000, Duration.ofMillis(1));
        when(embeddingModel.embed(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> new float[EmbeddingService.DIMENSIONS]).toList();
        });
        String text = "x".repeat(1497);

        List<float[]> vectors = limited.embedAll(List.of(text, text, text, text, text, text));

        assertEquals(6, vectors.size());
        verify(embeddingModel, times(2)).embed(anyList());
    }

    @Test
    void vectorLiteralIsPgvectorTextFormat() {
        assertEquals("[1.0,0.5,-2.0]", EmbeddingService.toVectorLiteral(new float[]{1f, 0.5f, -2f}));
    }

    private static EmbeddingRateLimitException rateLimit() {
        return new EmbeddingRateLimitException(null);
    }
}
