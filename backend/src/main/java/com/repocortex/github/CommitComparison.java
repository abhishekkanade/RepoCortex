package com.repocortex.github;

import java.util.List;

// status is GitHub's "ahead", "behind", "diverged" or "identical"
public record CommitComparison(String status, List<String> changedPaths, List<String> removedPaths, int filesListed) {
}
