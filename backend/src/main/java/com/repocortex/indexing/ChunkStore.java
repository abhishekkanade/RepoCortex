package com.repocortex.indexing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Component
public class ChunkStore {

    private final JdbcTemplate jdbc;

    public ChunkStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // The embedding is taken straight from embedding_cache, so vectors never travel back through Java.
    public void insert(UUID repoId, String commitSha, List<Chunk> chunks) {
        List<Object[]> rows = new ArrayList<>(chunks.size());
        for (Chunk c : chunks) {
            rows.add(new Object[]{repoId, commitSha, c.filePath(), c.startLine(), c.endLine(),
                    c.language(), c.content(), c.contentHash(), c.contentHash()});
        }
        int[] counts = jdbc.batchUpdate("""
                INSERT INTO code_chunks
                    (repo_id, commit_sha, file_path, start_line, end_line, language, content, content_hash, embedding)
                SELECT ?, ?, ?, ?, ?, ?, ?, ?, embedding FROM embedding_cache WHERE content_hash = ?
                """, rows);
        for (int count : counts) {
            if (count != 1) {
                throw new IllegalStateException("Chunk embedding missing from cache");
            }
        }
    }

    // Copies chunks of unchanged files from the old commit to the new one; returns rows copied.
    public int copyForward(UUID repoId, String fromSha, String toSha, Collection<String> excludedPaths) {
        return jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO code_chunks
                        (repo_id, commit_sha, file_path, start_line, end_line, language, content, content_hash, embedding)
                    SELECT repo_id, ?, file_path, start_line, end_line, language, content, content_hash, embedding
                    FROM code_chunks
                    WHERE repo_id = ? AND commit_sha = ? AND file_path <> ALL (?)
                    """);
            ps.setString(1, toSha);
            ps.setObject(2, repoId);
            ps.setString(3, fromSha);
            ps.setArray(4, con.createArrayOf("text", excludedPaths.toArray()));
            return ps;
        });
    }

    public void deleteCommit(UUID repoId, String commitSha) {
        jdbc.update("DELETE FROM code_chunks WHERE repo_id = ? AND commit_sha = ?", repoId, commitSha);
    }

    public boolean existsForCommit(UUID repoId, String commitSha) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM code_chunks WHERE repo_id = ? AND commit_sha = ?)",
                Boolean.class, repoId, commitSha);
        return Boolean.TRUE.equals(exists);
    }

    public int countForCommit(UUID repoId, String commitSha) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM code_chunks WHERE repo_id = ? AND commit_sha = ?",
                Integer.class, repoId, commitSha);
        return count == null ? 0 : count;
    }
}
