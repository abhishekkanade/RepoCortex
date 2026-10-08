package com.repocortex.indexing;

// content already starts with the "File: ... (lines ...)" header and is what gets embedded
public record Chunk(String filePath, int startLine, int endLine, String language, String content, String contentHash) {
}
