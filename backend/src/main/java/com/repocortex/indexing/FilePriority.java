package com.repocortex.indexing;

import java.util.Locale;
import java.util.Set;

// Lower number = indexed earlier, so early chat already knows the most useful files.
public final class FilePriority {

    public static final int DOCS = 0;
    public static final int MANIFEST = 1;
    public static final int SOURCE = 2;
    public static final int TEST = 3;
    public static final int OTHER = 4;

    private static final Set<String> MANIFESTS = Set.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
            "package.json", "tsconfig.json", "cargo.toml", "go.mod", "pyproject.toml", "setup.py",
            "setup.cfg", "requirements.txt", "gemfile", "composer.json", "build.sbt", "mix.exs",
            "cmakelists.txt", "makefile", "dockerfile", "docker-compose.yml", "docker-compose.yaml",
            "compose.yml", "compose.yaml", "pubspec.yaml", "deno.json");

    private FilePriority() {
    }

    public static int of(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        String fileName = lower.substring(lower.lastIndexOf('/') + 1);

        if (fileName.startsWith("readme") || lower.startsWith("docs/") || lower.startsWith("doc/")
                || fileName.endsWith(".md") || fileName.endsWith(".mdx")
                || fileName.endsWith(".rst") || fileName.endsWith(".adoc")) {
            return DOCS;
        }
        if (MANIFESTS.contains(fileName)) {
            return MANIFEST;
        }
        if (isTest(lower, fileName)) {
            return TEST;
        }
        if (SourceFileFilter.isCode(SourceFileFilter.languageOf(path))) {
            return SOURCE;
        }
        return OTHER;
    }

    private static boolean isTest(String lowerPath, String fileName) {
        String withSlash = "/" + lowerPath;
        return withSlash.contains("/test/") || withSlash.contains("/tests/")
                || withSlash.contains("/__tests__/") || withSlash.contains("/spec/")
                || fileName.matches(".*(test|tests)\\.(java|kt|scala|groovy|cs)$")
                || fileName.matches(".*_test\\.(go|py|rb|exs)$")
                || fileName.matches("test_.*\\.py$")
                || fileName.matches(".*\\.(test|spec)\\.[a-z]+$");
    }
}
