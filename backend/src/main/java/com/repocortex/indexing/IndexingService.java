package com.repocortex.indexing;

import com.repocortex.github.CommitComparison;
import com.repocortex.github.GitHubClient;
import com.repocortex.repo.RepoRepository;
import com.repocortex.repo.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

// Runs one indexing job: download -> filter -> chunk -> embed -> store, then summary.
// Not @Transactional: each step writes directly, so progress is visible while the job runs.
@Service
public class IndexingService {

    private static final Logger log = LoggerFactory.getLogger(IndexingService.class);

    static final int MAX_FILES = 1500;
    // GitHub's compare API lists at most 300 files, so 300 means the list may be cut off
    static final int MAX_CHANGED_FILES = 300;
    private static final int MAX_MANIFESTS_FOR_SUMMARY = 3;

    private final RepoRepository repoRepository;
    private final GitHubClient gitHubClient;
    private final TarballDownloader downloader;
    private final EmbeddingService embeddingService;
    private final ChunkStore chunkStore;
    private final IndexingStatusStore statusStore;
    private final RepoSummarizer summarizer;

    public IndexingService(RepoRepository repoRepository, GitHubClient gitHubClient, TarballDownloader downloader,
                           EmbeddingService embeddingService, ChunkStore chunkStore,
                           IndexingStatusStore statusStore, RepoSummarizer summarizer) {
        this.repoRepository = repoRepository;
        this.gitHubClient = gitHubClient;
        this.downloader = downloader;
        this.embeddingService = embeddingService;
        this.chunkStore = chunkStore;
        this.statusStore = statusStore;
        this.summarizer = summarizer;
    }

    public void index(UUID repoId) {
        Repository repo = repoRepository.findById(repoId).orElse(null);
        if (repo == null) {
            return;
        }
        String newSha = repo.getCommitSha();
        String oldSha = repo.getIndexedCommitSha();
        if (newSha.equals(oldSha)) {
            statusStore.markReady(repoId, newSha, chunkStore.countForCommit(repoId, newSha));
            return;
        }

        long startedAt = System.currentTimeMillis();
        statusStore.markIndexing(repoId);
        Snapshot snapshot;
        try {
            // leftovers from an earlier failed attempt on the same commit
            chunkStore.deleteCommit(repoId, newSha);
            snapshot = indexCommit(repo, newSha, oldSha);
            if (oldSha != null) {
                chunkStore.deleteCommit(repoId, oldSha);
            }
        } catch (Exception e) {
            log.warn("Indexing {} failed: {}", repo.getFullName(), e.getMessage(), e);
            try {
                chunkStore.deleteCommit(repoId, newSha);
            } finally {
                statusStore.markFailed(repoId, e.getMessage());
            }
            return;
        }
        log.info("Indexed {} at {} in {} ms", repo.getFullName(), newSha, System.currentTimeMillis() - startedAt);

        // the repo is already READY; a failed summary should not fail the index
        try {
            String summary = summarizer.summarize(repo.getFullName(), snapshot.readme(), snapshot.fileTree(), snapshot.manifests());
            statusStore.saveSummary(repoId, summary);
        } catch (Exception e) {
            log.warn("Summary for {} failed: {}", repo.getFullName(), e.getMessage());
        }
    }

    private Snapshot indexCommit(Repository repo, String newSha, String oldSha) throws Exception {
        UUID repoId = repo.getId();

        // incremental: reuse chunks of unchanged files, only re-index the changed ones
        Set<String> onlyPaths = null;
        int copiedChunks = 0;
        if (oldSha != null) {
            CommitComparison comparison = compareForIncremental(repo, oldSha, newSha);
            if (comparison != null) {
                Set<String> touched = new HashSet<>(comparison.changedPaths());
                touched.addAll(comparison.removedPaths());
                copiedChunks = chunkStore.copyForward(repoId, oldSha, newSha, touched);
                onlyPaths = new HashSet<>(comparison.changedPaths());
                log.info("Incremental index of {}: {} changed, {} removed, {} chunks copied",
                        repo.getFullName(), comparison.changedPaths().size(), comparison.removedPaths().size(), copiedChunks);
            }
        }

        Snapshot snapshot = download(repo, newSha, onlyPaths);
        List<SourceFile> files = snapshot.files();
        int filesTotal = Math.min(snapshot.eligibleCount(), MAX_FILES);
        statusStore.saveFilesTotal(repoId, filesTotal, snapshot.fileTree());

        int reusedFiles = Math.max(filesTotal - files.size(), 0);
        try (ChunkPipeline pipeline = new ChunkPipeline(repoId, newSha, reusedFiles, copiedChunks,
                embeddingService, chunkStore, statusStore)) {
            for (SourceFile file : files) {
                pipeline.addFile(Chunker.chunk(file.path(), file.language(), file.content()));
            }
            pipeline.finish();
            statusStore.markReady(repoId, newSha, pipeline.chunkCount());
            log.info("{}: {} files indexed, {} chunks, {} new embeddings",
                    repo.getFullName(), files.size(), pipeline.chunkCount(), pipeline.embeddedCount());
        }
        return snapshot;
    }

    // returns null when a full index is needed
    private CommitComparison compareForIncremental(Repository repo, String oldSha, String newSha) {
        try {
            CommitComparison comparison = gitHubClient.compareCommits(repo.getOwner(), repo.getName(), oldSha, newSha);
            // "diverged"/"behind" (e.g. force push) would compare against the merge base, not the old commit
            if (!"ahead".equals(comparison.status())) {
                log.info("Full index of {}: compare status is {}", repo.getFullName(), comparison.status());
                return null;
            }
            if (comparison.filesListed() >= MAX_CHANGED_FILES) {
                log.info("Full index of {}: too many changed files", repo.getFullName());
                return null;
            }
            return comparison;
        } catch (RuntimeException e) {
            log.info("Full index of {}: compare failed ({})", repo.getFullName(), e.getMessage());
            return null;
        }
    }

    // onlyPaths == null means keep every eligible file
    private Snapshot download(Repository repo, String sha, Set<String> onlyPaths) {
        TopFilesCollector collector = new TopFilesCollector(MAX_FILES);
        List<String> eligiblePaths = new ArrayList<>();
        Map<String, String> manifests = new TreeMap<>();
        String[] readme = new String[1];

        downloader.download(repo.getOwner(), repo.getName(), sha, new TarballDownloader.EntryHandler() {
            @Override
            public boolean wants(String path, long size) {
                return SourceFileFilter.isCandidate(path, size);
            }

            @Override
            public void accept(String path, byte[] bytes) {
                if (!SourceFileFilter.isTextContent(bytes)) {
                    return;
                }
                String content = new String(bytes, StandardCharsets.UTF_8);
                if (SourceFileFilter.looksMinified(content)) {
                    return;
                }
                eligiblePaths.add(path);
                int priority = FilePriority.of(path);

                // root README and manifests feed the summary, even when the file itself is unchanged
                boolean atRoot = !path.contains("/");
                if (atRoot && readme[0] == null && path.toLowerCase(Locale.ROOT).startsWith("readme")) {
                    readme[0] = content;
                }
                if (atRoot && priority == FilePriority.MANIFEST && manifests.size() < MAX_MANIFESTS_FOR_SUMMARY) {
                    manifests.put(path, content);
                }

                if (onlyPaths == null || onlyPaths.contains(path)) {
                    collector.add(new SourceFile(path, SourceFileFilter.languageOf(path), content, priority));
                }
            }
        });

        if (collector.dropped() > 0) {
            log.info("{}: kept {} files, dropped {} over the {} file limit",
                    repo.getFullName(), MAX_FILES, collector.dropped(), MAX_FILES);
        }
        return new Snapshot(collector.sortedFiles(), eligiblePaths.size(),
                FileTreeBuilder.build(eligiblePaths), readme[0], manifests);
    }

    private record Snapshot(List<SourceFile> files, int eligibleCount, String fileTree,
                            String readme, Map<String, String> manifests) {
    }
}
