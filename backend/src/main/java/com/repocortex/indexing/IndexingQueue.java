package com.repocortex.indexing;

import com.repocortex.common.ServiceBusyException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

// 2 indexing workers (virtual threads) + a queue of 20. One job per repo at a time.
@Component
public class IndexingQueue {

    private static final Logger log = LoggerFactory.getLogger(IndexingQueue.class);

    static final int WORKERS = 2;
    static final int QUEUE_SIZE = 20;

    private final IndexingService indexingService;
    // repos that are queued or running right now
    private final Set<UUID> activeRepoIds = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            WORKERS, WORKERS, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_SIZE),
            Thread.ofVirtual().name("indexer-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());

    public IndexingQueue(IndexingService indexingService) {
        this.indexingService = indexingService;
    }

    public boolean isActive(UUID repoId) {
        return activeRepoIds.contains(repoId);
    }

    public void enqueue(UUID repoId) {
        if (!activeRepoIds.add(repoId)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    indexingService.index(repoId);
                } catch (Exception e) {
                    log.error("Indexing job for {} crashed", repoId, e);
                } finally {
                    activeRepoIds.remove(repoId);
                }
            });
        } catch (RejectedExecutionException e) {
            activeRepoIds.remove(repoId);
            throw new ServiceBusyException("The indexer is busy, please try again shortly");
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
