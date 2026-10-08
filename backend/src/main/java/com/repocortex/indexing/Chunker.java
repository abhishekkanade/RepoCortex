package com.repocortex.indexing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

// Splits a file into overlapping line ranges. Line numbers are 1-based and inclusive.
public final class Chunker {

    static final int MIN_LINES = 60;
    static final int MAX_LINES = 80;
    static final int OVERLAP = 10;
    // ~1.5-2k tokens: fits gemini-embedding-001's 2048 token input (longer input is cut off silently)
    static final int MAX_CHARS = 6000;
    static final int MAX_LINE_CHARS = 1000;

    private Chunker() {
    }

    public static List<Chunk> chunk(String path, String language, String content) {
        List<String> lines = splitLines(content);
        List<Chunk> chunks = new ArrayList<>();
        int total = lines.size();
        int start = 0;

        while (start < total) {
            int end = findEnd(lines, start);
            String body = String.join("\n", lines.subList(start, end));
            if (!body.isBlank()) {
                chunks.add(toChunk(path, language, start + 1, end, body));
            }
            if (end >= total) {
                break;
            }
            // overlap with the previous chunk, unless it was so short that we'd repeat most of it
            start = end - start > 2 * OVERLAP ? end - OVERLAP : end;
        }
        return chunks;
    }

    // returns the exclusive end index of the chunk starting at start
    private static int findEnd(List<String> lines, int start) {
        int total = lines.size();
        int end = start;
        int chars = 0;
        while (end < total && end - start < MAX_LINES) {
            int lineChars = lines.get(end).length() + 1;
            if (end > start && chars + lineChars > MAX_CHARS) {
                break;
            }
            chars += lineChars;
            end++;
        }

        // a short tail would be mostly overlap, so take it into this chunk when it fits
        if (end < total && total - end <= OVERLAP && fits(lines, start, total)) {
            return total;
        }

        // prefer to cut after a blank line between MIN_LINES and the end
        if (end < total && end - start > MIN_LINES) {
            for (int i = end - 1; i >= start + MIN_LINES; i--) {
                if (lines.get(i).isBlank()) {
                    return i + 1;
                }
            }
        }
        return end;
    }

    private static boolean fits(List<String> lines, int start, int end) {
        if (end - start > MAX_LINES + OVERLAP) {
            return false;
        }
        int chars = 0;
        for (int i = start; i < end; i++) {
            chars += lines.get(i).length() + 1;
        }
        return chars <= MAX_CHARS;
    }

    private static List<String> splitLines(String content) {
        String[] raw = content.split("\r?\n", -1);
        int count = raw.length;
        // a trailing newline does not start a new line
        if (count > 0 && raw[count - 1].isEmpty()) {
            count--;
        }
        List<String> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String line = raw[i];
            lines.add(line.length() > MAX_LINE_CHARS ? line.substring(0, MAX_LINE_CHARS) + " ..." : line);
        }
        return lines;
    }

    private static Chunk toChunk(String path, String language, int startLine, int endLine, String body) {
        String text = "File: " + path + " (lines " + startLine + "-" + endLine + ")\n" + body;
        return new Chunk(path, startLine, endLine, language, text, sha256(text));
    }

    static String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
