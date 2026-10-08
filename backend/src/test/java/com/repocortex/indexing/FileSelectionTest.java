package com.repocortex.indexing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSelectionTest {

    @ParameterizedTest
    @ValueSource(strings = {"src/App.java", "README.md", "pom.xml", "Dockerfile", "docker/Dockerfile.dev",
            "Makefile", "web/app/page.tsx", "config/application.yml"})
    void acceptsSourceConfigAndDocs(String path) {
        assertTrue(SourceFileFilter.isCandidate(path, 1000));
    }

    @ParameterizedTest
    @ValueSource(strings = {"node_modules/x/index.js", "web/dist/app.js", "target/classes/A.java",
            "build/gen.ts", "package-lock.json", "yarn.lock", "static/app.min.js", "logo.png",
            "lib/tool.jar", "LICENSE"})
    void rejectsSkippedDirsLockfilesMinifiedAndUnknownTypes(String path) {
        assertFalse(SourceFileFilter.isCandidate(path, 1000));
    }

    @Test
    void rejectsLargeFilesBinaryContentAndMinifiedText() {
        assertFalse(SourceFileFilter.isCandidate("src/Big.java", SourceFileFilter.MAX_FILE_BYTES + 1));
        assertFalse(SourceFileFilter.isTextContent(new byte[]{'a', 0, 'b'}));
        assertTrue(SourceFileFilter.isTextContent("hello".getBytes(StandardCharsets.UTF_8)));
        assertTrue(SourceFileFilter.looksMinified("x".repeat(5000)));
        assertFalse(SourceFileFilter.looksMinified("int a = 1;\nint b = 2;\n"));
    }

    @Test
    void detectsLanguage() {
        assertEquals("java", SourceFileFilter.languageOf("src/App.java"));
        assertEquals("typescript", SourceFileFilter.languageOf("app/page.tsx"));
        assertEquals("dockerfile", SourceFileFilter.languageOf("Dockerfile"));
        assertEquals("markdown", SourceFileFilter.languageOf("docs/intro.md"));
    }

    @Test
    void ordersDocsThenManifestsThenSourceThenTestsThenOther() {
        assertEquals(FilePriority.DOCS, FilePriority.of("README.md"));
        assertEquals(FilePriority.DOCS, FilePriority.of("docs/setup.txt"));
        assertEquals(FilePriority.MANIFEST, FilePriority.of("pom.xml"));
        assertEquals(FilePriority.MANIFEST, FilePriority.of("web/package.json"));
        assertEquals(FilePriority.SOURCE, FilePriority.of("src/main/java/App.java"));
        assertEquals(FilePriority.TEST, FilePriority.of("src/test/java/AppTest.java"));
        assertEquals(FilePriority.TEST, FilePriority.of("web/button.test.tsx"));
        assertEquals(FilePriority.TEST, FilePriority.of("pkg/server_test.go"));
        assertEquals(FilePriority.OTHER, FilePriority.of("src/main/resources/application.yml"));
    }

    @Test
    void collectorKeepsTheBestFilesWhenOverTheLimit() {
        TopFilesCollector collector = new TopFilesCollector(2);
        collector.add(file("z.yml", FilePriority.OTHER));
        collector.add(file("App.java", FilePriority.SOURCE));
        collector.add(file("README.md", FilePriority.DOCS));
        collector.add(file("data.json", FilePriority.OTHER));

        List<String> kept = collector.sortedFiles().stream().map(SourceFile::path).toList();

        assertEquals(List.of("README.md", "App.java"), kept);
        assertEquals(2, collector.dropped());
    }

    @Test
    void fileTreeGroupsByFolder() {
        String tree = FileTreeBuilder.build(List.of("src/b/B.java", "pom.xml", "src/b/A.java"));

        assertEquals("pom.xml\nsrc/b/\n  A.java\n  B.java\n", tree);
    }

    private static SourceFile file(String path, int priority) {
        return new SourceFile(path, "x", "", priority);
    }
}
