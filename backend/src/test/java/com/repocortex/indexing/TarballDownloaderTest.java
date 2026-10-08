package com.repocortex.indexing;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TarballDownloaderTest {

    @Test
    void streamsFilesStripsTopFolderAndSkipsUnwantedOnes() throws IOException {
        byte[] tarball = tarGz(Map.of(
                "owner-repo-abc123/README.md", "# Hello\n",
                "owner-repo-abc123/src/App.java", "class App {}\n",
                "owner-repo-abc123/node_modules/lib/index.js", "x\n",
                "owner-repo-abc123/package-lock.json", "{}\n",
                "owner-repo-abc123/logo.png", "png\n"));

        Map<String, String> received = new LinkedHashMap<>();
        new TarballDownloader().readTarball(new ByteArrayInputStream(tarball), new TarballDownloader.EntryHandler() {
            @Override
            public boolean wants(String path, long size) {
                return SourceFileFilter.isCandidate(path, size);
            }

            @Override
            public void accept(String path, byte[] content) {
                received.put(path, new String(content, StandardCharsets.UTF_8));
            }
        });

        assertEquals(Map.of("README.md", "# Hello\n", "src/App.java", "class App {}\n"), received);
    }

    private static byte[] tarGz(Map<String, String> files) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(out))) {
            for (var file : files.entrySet()) {
                byte[] bytes = file.getValue().getBytes(StandardCharsets.UTF_8);
                TarArchiveEntry entry = new TarArchiveEntry(file.getKey());
                entry.setSize(bytes.length);
                tar.putArchiveEntry(entry);
                tar.write(bytes);
                tar.closeArchiveEntry();
            }
        }
        return out.toByteArray();
    }
}
