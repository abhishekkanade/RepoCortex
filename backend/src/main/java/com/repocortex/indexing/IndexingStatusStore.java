package com.repocortex.indexing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

// Small targeted UPDATEs for the indexing job, so progress writes never overwrite other columns.
@Component
public class IndexingStatusStore {

    private final JdbcTemplate jdbc;

    public IndexingStatusStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void markIndexing(UUID repoId) {
        jdbc.update("""
                UPDATE repositories
                SET status = 'INDEXING', files_total = 0, files_processed = 0, chunk_count = 0,
                    error_message = NULL, updated_at = now()
                WHERE id = ?
                """, repoId);
    }

    public void saveFilesTotal(UUID repoId, int filesTotal, String fileTree) {
        jdbc.update("UPDATE repositories SET files_total = ?, file_tree = ?, updated_at = now() WHERE id = ?",
                filesTotal, fileTree, repoId);
    }

    public void saveProgress(UUID repoId, int filesProcessed, int chunkCount) {
        jdbc.update("UPDATE repositories SET files_processed = ?, chunk_count = ?, updated_at = now() WHERE id = ?",
                filesProcessed, chunkCount, repoId);
    }

    public void markReady(UUID repoId, String commitSha, int chunkCount) {
        jdbc.update("""
                UPDATE repositories
                SET status = 'READY', commit_sha = ?, indexed_commit_sha = ?, files_processed = files_total,
                    chunk_count = ?, error_message = NULL, indexed_at = now(), updated_at = now()
                WHERE id = ?
                """, commitSha, commitSha, chunkCount, repoId);
    }

    public void saveSummary(UUID repoId, String summary) {
        jdbc.update("UPDATE repositories SET summary = ?, updated_at = now() WHERE id = ?", summary, repoId);
    }

    // With an older complete index the repo goes back to READY on that commit; otherwise it is FAILED.
    public void markFailed(UUID repoId, String message) {
        jdbc.update("""
                UPDATE repositories r
                SET status = CASE WHEN r.indexed_commit_sha IS NULL THEN 'FAILED' ELSE 'READY' END,
                    commit_sha = COALESCE(r.indexed_commit_sha, r.commit_sha),
                    chunk_count = (SELECT count(*) FROM code_chunks c
                                   WHERE c.repo_id = r.id AND c.commit_sha = r.indexed_commit_sha),
                    error_message = ?,
                    updated_at = now()
                WHERE r.id = ?
                """, truncate(message), repoId);
    }

    // After a restart nothing is running, so any queued or running job was lost.
    public int recoverInterrupted() {
        jdbc.update("""
                DELETE FROM code_chunks c
                USING repositories r
                WHERE c.repo_id = r.id AND c.commit_sha IS DISTINCT FROM r.indexed_commit_sha
                """);
        return jdbc.update("""
                UPDATE repositories r
                SET status = CASE WHEN r.indexed_commit_sha IS NULL THEN 'FAILED' ELSE 'READY' END,
                    commit_sha = COALESCE(r.indexed_commit_sha, r.commit_sha),
                    chunk_count = (SELECT count(*) FROM code_chunks c
                                   WHERE c.repo_id = r.id AND c.commit_sha = r.indexed_commit_sha),
                    error_message = 'Indexing was interrupted',
                    updated_at = now()
                WHERE r.status IN ('PENDING', 'INDEXING')
                """);
    }

    private static String truncate(String message) {
        if (message == null) {
            return "Indexing failed";
        }
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }
}
