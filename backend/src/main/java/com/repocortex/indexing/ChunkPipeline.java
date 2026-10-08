package com.repocortex.indexing;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

// One per indexing job. Collects chunks into batches of up to 64 chunks, and with a token budget also
// up to one API request's worth of tokens, so batches finish in priority order. Each batch is embedded (cache misses only)
// and stored on a virtual thread, with at most 2 batches in flight. Saves progress every ~10 files.
class ChunkPipeline implements AutoCloseable {

    static final int BATCH_SIZE = 64;
    static final int MAX_IN_FLIGHT = 2;
    static final int PROGRESS_EVERY_FILES = 10;

    private final UUID repoId;
    private final String commitSha;
    private final EmbeddingService embeddingService;
    private final ChunkStore chunkStore;
    private final IndexingStatusStore statusStore;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore inFlight = new Semaphore(MAX_IN_FLIGHT);
    private final List<Future<?>> futures = new ArrayList<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    private final int maxBatchTokens;
    private final List<Chunk> pending = new ArrayList<>();
    private int pendingTokens;
    // number of files whose last chunk is in "pending"
    private int pendingFilesDone;

    private final AtomicInteger filesProcessed;
    private final AtomicInteger chunkCount;
    private final AtomicInteger embedded = new AtomicInteger();
    private int lastSavedFiles;

    // initial counts cover files/chunks reused from the previous commit
    ChunkPipeline(UUID repoId, String commitSha, int initialFiles, int initialChunkCount,
                  EmbeddingService embeddingService, ChunkStore chunkStore, IndexingStatusStore statusStore) {
        this.repoId = repoId;
        this.commitSha = commitSha;
        this.filesProcessed = new AtomicInteger(initialFiles);
        this.lastSavedFiles = initialFiles;
        this.chunkCount = new AtomicInteger(initialChunkCount);
        this.embeddingService = embeddingService;
        this.chunkStore = chunkStore;
        this.statusStore = statusStore;
        this.maxBatchTokens = embeddingService.maxRequestTokens();
    }

    void addFile(List<Chunk> chunks) throws InterruptedException {
        throwIfFailed();
        if (chunks.isEmpty()) {
            onBatchDone(0, 1);
            return;
        }
        for (Chunk chunk : chunks) {
            int tokens = TokenRateLimiter.estimateTokens(chunk.content());
            if (!pending.isEmpty() && (pending.size() >= BATCH_SIZE || pendingTokens + tokens > maxBatchTokens)) {
                flush();
            }
            pending.add(chunk);
            pendingTokens += tokens;
        }
        // a file only counts as done once the batch holding its last chunk is stored
        pendingFilesDone++;
    }

    // Flushes the last batch and waits for everything; throws the first error from any batch.
    void finish() throws Exception {
        if (!pending.isEmpty() || pendingFilesDone > 0) {
            flush();
        }
        for (Future<?> future : futures) {
            future.get();
        }
        throwIfFailed();
        saveProgress(true);
    }

    int chunkCount() {
        return chunkCount.get();
    }

    int embeddedCount() {
        return embedded.get();
    }

    private void flush() throws InterruptedException {
        submit(new ArrayList<>(pending), pendingFilesDone);
        pending.clear();
        pendingTokens = 0;
        pendingFilesDone = 0;
    }

    private void submit(List<Chunk> batch, int filesDone) throws InterruptedException {
        inFlight.acquire();
        futures.add(executor.submit(() -> {
            try {
                // another batch already failed, the job is going to fail anyway
                if (failure.get() != null) {
                    return;
                }
                if (!batch.isEmpty()) {
                    embedded.addAndGet(embeddingService.ensureCached(batch));
                    chunkStore.insert(repoId, commitSha, batch);
                }
                onBatchDone(batch.size(), filesDone);
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            } finally {
                inFlight.release();
            }
        }));
    }

    private void onBatchDone(int chunks, int files) {
        chunkCount.addAndGet(chunks);
        filesProcessed.addAndGet(files);
        saveProgress(false);
    }

    private synchronized void saveProgress(boolean force) {
        int files = filesProcessed.get();
        if (force || files - lastSavedFiles >= PROGRESS_EVERY_FILES) {
            lastSavedFiles = files;
            statusStore.saveProgress(repoId, files, chunkCount.get());
        }
    }

    private void throwIfFailed() {
        Throwable e = failure.get();
        if (e instanceof RuntimeException re) {
            throw re;
        }
        if (e != null) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
