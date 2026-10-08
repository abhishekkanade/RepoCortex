package com.repocortex.indexing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkerTest {

    // "line 1\nline 2\n..." so every line's text equals its 1-based number
    private static String numberedLines(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> "line " + i).collect(Collectors.joining("\n")) + "\n";
    }

    private static List<String> bodyLines(Chunk chunk) {
        List<String> lines = chunk.content().lines().toList();
        return lines.subList(1, lines.size()); // drop the "File: ..." header
    }

    @Test
    void smallFileIsOneChunkWithExactLines() {
        List<Chunk> chunks = Chunker.chunk("src/App.java", "java", "a\nb\nc\n");

        assertEquals(1, chunks.size());
        Chunk chunk = chunks.get(0);
        assertEquals(1, chunk.startLine());
        assertEquals(3, chunk.endLine());
        assertEquals("File: src/App.java (lines 1-3)\na\nb\nc", chunk.content());
    }

    @Test
    void trailingNewlineDoesNotAddALine() {
        assertEquals(2, Chunker.chunk("a.txt", "text", "x\ny\n").get(0).endLine());
        assertEquals(2, Chunker.chunk("a.txt", "text", "x\ny").get(0).endLine());
        assertEquals(2, Chunker.chunk("a.txt", "text", "x\r\ny\r\n").get(0).endLine());
    }

    @Test
    void emptyOrBlankFileHasNoChunks() {
        assertTrue(Chunker.chunk("a.txt", "text", "").isEmpty());
        assertTrue(Chunker.chunk("a.txt", "text", "\n  \n\n").isEmpty());
    }

    @Test
    void longFileIsSplitWithOverlapAndCorrectLineNumbers() {
        List<Chunk> chunks = Chunker.chunk("Big.java", "java", numberedLines(200));

        assertEquals(1, chunks.get(0).startLine());
        assertEquals(200, chunks.get(chunks.size() - 1).endLine());
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            int size = chunk.endLine() - chunk.startLine() + 1;
            assertTrue(size <= Chunker.MAX_LINES + Chunker.OVERLAP, "chunk too big: " + size);
            if (i > 0) {
                assertEquals(chunks.get(i - 1).endLine() - Chunker.OVERLAP + 1, chunk.startLine(), "overlap");
            }
        }
    }

    @Test
    void chunkBodyMatchesItsLineNumbers() {
        for (Chunk chunk : Chunker.chunk("Big.java", "java", numberedLines(250))) {
            List<String> body = bodyLines(chunk);
            assertEquals(chunk.endLine() - chunk.startLine() + 1, body.size());
            assertEquals("line " + chunk.startLine(), body.get(0));
            assertEquals("line " + chunk.endLine(), body.get(body.size() - 1));
            assertTrue(chunk.content().startsWith(
                    "File: Big.java (lines " + chunk.startLine() + "-" + chunk.endLine() + ")\n"));
        }
    }

    @Test
    void prefersToCutAfterABlankLine() {
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 150; i++) {
            content.append(i == 70 ? "" : "code " + i).append('\n');
        }

        Chunk first = Chunker.chunk("A.java", "java", content.toString()).get(0);

        assertEquals(1, first.startLine());
        assertEquals(70, first.endLine());
    }

    @Test
    void shortTailIsMergedIntoTheLastChunk() {
        List<Chunk> chunks = Chunker.chunk("A.java", "java", numberedLines(85));

        assertEquals(1, chunks.size());
        assertEquals(85, chunks.get(0).endLine());
    }

    @Test
    void longLinesAreCappedBySize() {
        String longLine = "x".repeat(900);
        String content = IntStream.range(0, 40).mapToObj(i -> longLine).collect(Collectors.joining("\n"));

        List<Chunk> chunks = Chunker.chunk("data.txt", "text", content);

        assertTrue(chunks.size() > 1);
        assertEquals(40, chunks.get(chunks.size() - 1).endLine());
        for (Chunk chunk : chunks) {
            assertTrue(chunk.content().length() <= Chunker.MAX_CHARS + 100, "chunk over size cap");
        }
    }

    @Test
    void contentHashIsSha256OfChunkText() {
        Chunk chunk = Chunker.chunk("a.txt", "text", "hello\n").get(0);

        assertEquals(Chunker.sha256(chunk.content()), chunk.contentHash());
        assertEquals(64, chunk.contentHash().length());
    }
}
