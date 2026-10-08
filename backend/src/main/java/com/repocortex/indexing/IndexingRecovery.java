package com.repocortex.indexing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// Jobs live in memory, so a restart loses them. Clean up whatever they left behind.
@Component
public class IndexingRecovery {

    private static final Logger log = LoggerFactory.getLogger(IndexingRecovery.class);

    private final IndexingStatusStore statusStore;

    public IndexingRecovery(IndexingStatusStore statusStore) {
        this.statusStore = statusStore;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        int repos = statusStore.recoverInterrupted();
        if (repos > 0) {
            log.warn("Marked {} interrupted indexing job(s) as failed", repos);
        }
    }
}
