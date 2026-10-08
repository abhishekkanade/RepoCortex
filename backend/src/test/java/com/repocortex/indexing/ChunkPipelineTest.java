package com.repocortex.indexing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChunkPipelineTest {

    private final EmbeddingService embeddingService = mock(EmbeddingService.class);
    private final ChunkStore chunkStore = mock(ChunkStore.class);
    private final IndexingStatusStore statusStore = mock(IndexingStatusStore.class);
    private final UUID repoId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(embeddingService.maxRequestTokens()).thenReturn(Integer.MAX_VALUE);
    }

    @Test
    void storesAllChunksInBatchesAndCountsThem() throws Exception {
        try (ChunkPipeline pipeline = new ChunkPipeline(repoId, "sha", 0, 0, embeddingService, chunkStore, statusStore)) {
            for (int i = 0; i < 30; i++) {
                pipeline.addFile(chunks("File" + i + ".java", 5));
            }
            pipeline.finish();

            assertEquals(150, pipeline.chunkCount());
        }
        verify(chunkStore, times(3)).insert(any(), anyString(), anyList()); // 64 + 64 + 22
        verify(statusStore, atLeastOnce()).saveProgress(repoId, 30, 150);
    }

    @Test
    void tokenBudgetMakesSmallerBatchesInOrder() throws Exception {
        // each chunk is ~1000 estimated tokens, so 3000 tokens per batch -> 3 chunks per batch
        when(embeddingService.maxRequestTokens()).thenReturn(3000);
        List<List<String>> stored = new ArrayList<>();
        doAnswer(inv -> {
            List<Chunk> batch = inv.getArgument(2);
            synchronized (stored) {
                stored.add(batch.stream().map(Chunk::filePath).toList());
            }
            return null;
        }).when(chunkStore).insert(any(), anyString(), anyList());

        try (ChunkPipeline pipeline = new ChunkPipeline(repoId, "sha", 0, 0, embeddingService, chunkStore, statusStore)) {
            pipeline.addFile(bigChunks("README.md", 2));
            pipeline.addFile(bigChunks("App.java", 4));
            pipeline.finish();
        }

        assertEquals(2, stored.size());
        assertTrue(stored.contains(List.of("README.md", "README.md", "App.java")));
        assertTrue(stored.contains(List.of("App.java", "App.java", "App.java")));
    }

    @Test
    void failedEmbeddingFailsTheJobAndCountsNothing() throws Exception {
        when(embeddingService.ensureCached(anyList())).thenThrow(new IllegalStateException("429: no credits"));

        try (ChunkPipeline pipeline = new ChunkPipeline(repoId, "sha", 0, 0, embeddingService, chunkStore, statusStore)) {
            Exception e = assertThrows(Exception.class, () -> {
                for (int i = 0; i < 30; i++) {
                    pipeline.addFile(chunks("File" + i + ".java", 5));
                }
                pipeline.finish();
            });

            assertEquals("429: no credits", e.getMessage());
            assertEquals(0, pipeline.chunkCount());
        }
        verify(chunkStore, never()).insert(any(), anyString(), anyList());
        verify(statusStore, never()).saveProgress(any(), anyInt(), anyInt());
    }

    private static List<Chunk> bigChunks(String path, int count) {
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            chunks.add(new Chunk(path, i + 1, i + 1, "java", "x".repeat(2997), path + i));
        }
        return chunks;
    }

    private static List<Chunk> chunks(String path, int count) {
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            chunks.add(new Chunk(path, i + 1, i + 1, "java", "c" + i, path + i));
        }
        return chunks;
    }
}
