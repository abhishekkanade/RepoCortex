package com.repocortex.indexing;

import java.util.Comparator;

public record SourceFile(String path, String language, String content, int priority) {

    // best first: lower priority number, then alphabetical path
    public static final Comparator<SourceFile> INDEX_ORDER =
            Comparator.comparingInt(SourceFile::priority).thenComparing(SourceFile::path);
}
