package com.repocortex.repo.dto;

import com.repocortex.repo.RepoStatus;
import com.repocortex.repo.Repository;

import java.time.Instant;
import java.util.UUID;

public record RepoResponse(
        UUID id,
        String owner,
        String name,
        String fullName,
        String description,
        String language,
        int stars,
        String defaultBranch,
        String commitSha,
        String indexedCommitSha,
        RepoStatus status,
        boolean chatReady,
        int filesTotal,
        int filesProcessed,
        int chunkCount,
        String errorMessage,
        String summary,
        Instant indexedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static RepoResponse from(Repository repo, boolean chatReady) {
        return new RepoResponse(
                repo.getId(),
                repo.getOwner(),
                repo.getName(),
                repo.getFullName(),
                repo.getDescription(),
                repo.getLanguage(),
                repo.getStars(),
                repo.getDefaultBranch(),
                repo.getCommitSha(),
                repo.getIndexedCommitSha(),
                repo.getStatus(),
                chatReady,
                repo.getFilesTotal(),
                repo.getFilesProcessed(),
                repo.getChunkCount(),
                repo.getErrorMessage(),
                repo.getSummary(),
                repo.getIndexedAt(),
                repo.getCreatedAt(),
                repo.getUpdatedAt()
        );
    }
}
