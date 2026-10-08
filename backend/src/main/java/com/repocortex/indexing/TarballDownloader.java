package com.repocortex.indexing;

import com.repocortex.common.BadRequestException;
import com.repocortex.github.GitHubApiException;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

// Streams the repo tarball from codeload and hands each file to a handler. Nothing is written to disk.
@Component
public class TarballDownloader {

    public static final long MAX_DOWNLOAD_BYTES = 50L * 1024 * 1024;

    public interface EntryHandler {
        // decide from path and size alone, so skipped files are never read into memory
        boolean wants(String path, long size);

        void accept(String path, byte[] content);
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public void download(String owner, String name, String commitSha, EntryHandler handler) {
        URI uri = URI.create("https://codeload.github.com/%s/%s/tar.gz/%s".formatted(owner, name, commitSha));
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build();

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new GitHubApiException("Could not download repository code", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Download interrupted", e);
        }

        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new GitHubApiException("Code download failed with status " + response.statusCode());
            }
            readTarball(body, handler);
        } catch (IOException e) {
            throw new GitHubApiException("Could not read repository code: " + e.getMessage(), e);
        }
    }

    void readTarball(InputStream body, EntryHandler handler) throws IOException {
        InputStream limited = new LimitedInputStream(body, MAX_DOWNLOAD_BYTES);
        try (TarArchiveInputStream tar = new TarArchiveInputStream(
                new GzipCompressorInputStream(new BufferedInputStream(limited)))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (!entry.isFile()) {
                    continue;
                }
                String path = stripTopFolder(entry.getName());
                if (path.isEmpty() || !handler.wants(path, entry.getSize())) {
                    continue;
                }
                // reads only the current entry
                handler.accept(path, tar.readAllBytes());
            }
        }
    }

    // GitHub tarballs wrap everything in "{owner}-{name}-{sha}/"
    static String stripTopFolder(String entryName) {
        int slash = entryName.indexOf('/');
        return slash < 0 ? "" : entryName.substring(slash + 1);
    }

    // fails the download as soon as more than maxBytes have been read from GitHub
    private static class LimitedInputStream extends FilterInputStream {

        private final long maxBytes;
        private long readBytes;

        LimitedInputStream(InputStream in, long maxBytes) {
            super(in);
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            count(skipped);
            return skipped;
        }

        private void count(long n) {
            readBytes += n;
            if (readBytes > maxBytes) {
                throw new BadRequestException("Repository download is larger than 50 MB");
            }
        }
    }
}
