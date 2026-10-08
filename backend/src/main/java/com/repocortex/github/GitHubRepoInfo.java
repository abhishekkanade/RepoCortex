package com.repocortex.github;

public record GitHubRepoInfo(
        String owner,
        String name,
        String description,
        String language,
        int stars,
        String defaultBranch,
        boolean isPrivate,
        long sizeKb
) {
}
