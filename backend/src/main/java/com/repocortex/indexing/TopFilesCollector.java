package com.repocortex.indexing;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

// Keeps at most maxFiles files in memory. When full, a better file replaces the worst one kept.
public class TopFilesCollector {

    private final int maxFiles;
    // head of the queue is the worst file kept so far
    private final PriorityQueue<SourceFile> kept;
    private int dropped;

    public TopFilesCollector(int maxFiles) {
        this.maxFiles = maxFiles;
        this.kept = new PriorityQueue<>(SourceFile.INDEX_ORDER.reversed());
    }

    public void add(SourceFile file) {
        if (kept.size() < maxFiles) {
            kept.add(file);
            return;
        }
        dropped++;
        if (SourceFile.INDEX_ORDER.compare(file, kept.peek()) < 0) {
            kept.poll();
            kept.add(file);
        }
    }

    public List<SourceFile> sortedFiles() {
        List<SourceFile> files = new ArrayList<>(kept);
        files.sort(SourceFile.INDEX_ORDER);
        return files;
    }

    public int dropped() {
        return dropped;
    }
}
