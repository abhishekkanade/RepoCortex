package com.repocortex.indexing;

import com.repocortex.github.CommitComparison;
import com.repocortex.github.GitHubClient;
import com.repocortex.repo.RepoRepository;
import com.repocortex.repo.RepoStatus;
import com.repocortex.repo.Repository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

// Runs the real indexing job against Postgres; only GitHub, OpenAI and the summary are faked.
@SpringBootTest(properties = "spring.docker.compose.skip.in-tests=false")
class IndexingServiceIntegrationTest {

    private static final String SHA_A = "a".repeat(40);
    private static final String SHA_B = "b".repeat(40);
    private static final String SHA_C = "c".repeat(40);

    @Autowired
    private IndexingService indexingService;
    @Autowired
    private RepoRepository repoRepository;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private EmbeddingModel embeddingModel;
    @MockitoBean
    private TarballDownloader downloader;
    @MockitoBean
    private GitHubClient gitHubClient;
    @MockitoBean
    private RepoSummarizer summarizer;

    // makes chunk text unique per run, so earlier runs' cache rows never match
    private final String salt = UUID.randomUUID().toString();
    private final List<String> embeddedTexts = new ArrayList<>();
    private final List<String> allEmbeddedHashes = new ArrayList<>();
    private Repository repo;

    @BeforeEach
    void setUp() {
        when(embeddingModel.embed(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            embeddedTexts.addAll(texts);
            texts.forEach(t -> allEmbeddedHashes.add(Chunker.sha256(t)));
            return texts.stream().map(t -> fakeVector()).toList();
        });
        when(summarizer.summarize(any(), any(), any(), any())).thenReturn("A test repo.");

        repo = new Repository();
        repo.setOwner("test");
        repo.setName("repo-" + salt);
        repo.setFullName("test/repo-" + salt);
        repo.setDefaultBranch("main");
        repo.setCommitSha(SHA_A);
        repo.setStatus(RepoStatus.PENDING);
        repo = repoRepository.save(repo);
    }

    @AfterEach
    void cleanUp() {
        // remove the fake vectors this test put in the shared cache
        for (String hash : allEmbeddedHashes) {
            jdbc.update("DELETE FROM embedding_cache WHERE content_hash = ?", hash);
        }
        repoRepository.deleteById(repo.getId());
    }

    @Test
    void fullThenIncrementalThenFailedReindex() {
        // 1. full index of commit A
        Map<String, String> filesA = new LinkedHashMap<>();
        filesA.put("README.md", "# Test " + salt + "\n");
        filesA.put("src/App.java", lines("app", 150));
        filesA.put("src/Old.java", lines("old", 10));
        filesA.put("node_modules/x/index.js", "skip me\n");
        serveTarball(SHA_A, filesA);

        indexingService.index(repo.getId());

        Repository afterA = reload();
        assertEquals(RepoStatus.READY, afterA.getStatus());
        assertEquals(SHA_A, afterA.getIndexedCommitSha());
        assertEquals(3, afterA.getFilesTotal());
        assertEquals("A test repo.", afterA.getSummary());
        assertTrue(afterA.getFileTree().contains("App.java"));
        int chunksA = countChunks(SHA_A);
        assertEquals(chunksA, afterA.getChunkCount());
        assertEquals(chunksA, embeddedTexts.size());
        assertEquals(chunksA, countCached());
        assertTrue(embeddedTexts.get(0).startsWith("File: README.md (lines 1-1)"), "docs come first");

        // 2. commit B changes Util.java (new) and removes Old.java: only Util is embedded
        repo = repoRepository.save(reloadWith(SHA_B));
        when(gitHubClient.compareCommits("test", repo.getName(), SHA_A, SHA_B))
                .thenReturn(new CommitComparison("ahead", List.of("src/Util.java"), List.of("src/Old.java"), 2));
        Map<String, String> filesB = new LinkedHashMap<>(filesA);
        filesB.remove("src/Old.java");
        filesB.put("src/Util.java", lines("util", 20));
        serveTarball(SHA_B, filesB);
        embeddedTexts.clear();

        indexingService.index(repo.getId());

        Repository afterB = reload();
        assertEquals(RepoStatus.READY, afterB.getStatus());
        assertEquals(SHA_B, afterB.getIndexedCommitSha());
        assertEquals(1, embeddedTexts.size());
        assertTrue(embeddedTexts.get(0).startsWith("File: src/Util.java"));
        assertEquals(0, countChunks(SHA_A), "old commit chunks deleted");
        assertEquals(0, countChunks(SHA_B, "src/Old.java"));
        assertEquals(1, countChunks(SHA_B, "src/Util.java"));
        assertEquals(countChunks(SHA_B), afterB.getChunkCount());

        // 3. commit C fails while embedding: old index B stays usable
        repo = repoRepository.save(reloadWith(SHA_C));
        when(gitHubClient.compareCommits("test", repo.getName(), SHA_B, SHA_C))
                .thenThrow(new IllegalStateException("compare failed"));
        Map<String, String> filesC = new LinkedHashMap<>(filesB);
        filesC.put("src/New.java", lines("new", 5));
        serveTarball(SHA_C, filesC);
        when(embeddingModel.embed(anyList())).thenThrow(new IllegalStateException("429: no credits"));

        indexingService.index(repo.getId());

        Repository afterC = reload();
        assertEquals(RepoStatus.READY, afterC.getStatus());
        assertEquals(SHA_B, afterC.getCommitSha());
        assertEquals(SHA_B, afterC.getIndexedCommitSha());
        assertEquals("429: no credits", afterC.getErrorMessage());
        assertEquals(0, countChunks(SHA_C), "partial chunks removed");
        assertEquals(afterB.getChunkCount(), countChunks(SHA_B));
        assertEquals(afterB.getChunkCount(), afterC.getChunkCount());
    }

    @Test
    void failedFirstIndexIsMarkedFailed() {
        serveTarball(SHA_A, Map.of("README.md", "# Test " + salt + "\n"));
        when(embeddingModel.embed(anyList())).thenThrow(new IllegalStateException("boom"));

        indexingService.index(repo.getId());

        Repository after = reload();
        assertEquals(RepoStatus.FAILED, after.getStatus());
        assertNull(after.getIndexedCommitSha());
        assertNotNull(after.getErrorMessage());
        assertEquals(0, countChunks(SHA_A));
    }

    private void serveTarball(String sha, Map<String, String> files) {
        doAnswer(inv -> {
            TarballDownloader.EntryHandler handler = inv.getArgument(3);
            files.forEach((path, content) -> {
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                if (handler.wants(path, bytes.length)) {
                    handler.accept(path, bytes);
                }
            });
            return null;
        }).when(downloader).download(any(), any(), eq(sha), any());
    }

    private String lines(String prefix, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            sb.append(prefix).append(' ').append(salt).append(' ').append(i).append('\n');
        }
        return sb.toString();
    }

    private Repository reload() {
        return repoRepository.findById(repo.getId()).orElseThrow();
    }

    private Repository reloadWith(String newSha) {
        Repository fresh = reload();
        fresh.setCommitSha(newSha);
        fresh.setStatus(RepoStatus.PENDING);
        return fresh;
    }

    private int countChunks(String sha) {
        return jdbc.queryForObject("SELECT count(*) FROM code_chunks WHERE repo_id = ? AND commit_sha = ?",
                Integer.class, repo.getId(), sha);
    }

    private int countChunks(String sha, String path) {
        return jdbc.queryForObject("SELECT count(*) FROM code_chunks WHERE repo_id = ? AND commit_sha = ? AND file_path = ?",
                Integer.class, repo.getId(), sha, path);
    }

    private int countCached() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM embedding_cache WHERE content_hash IN
                    (SELECT content_hash FROM code_chunks WHERE repo_id = ?)""", Integer.class, repo.getId());
    }

    private static float[] fakeVector() {
        float[] v = new float[1536];
        v[0] = 1f;
        return v;
    }
}
