package com.repocortex.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.repocortex.common.BadRequestException;
import com.repocortex.common.NotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
public class GitHubClient {

    // GitHub's "size" includes the full git history, so the code tarball is usually much smaller.
    // Only reject repos that are clearly too big here; the exact 50 MB check happens on download.
    private static final long MAX_REPO_SIZE_KB = 200 * 1024;

    private final RestClient restClient;

    public GitHubClient(@Value("${app.github.token}") String token) {
        this.restClient = RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .build();
    }

    public GitHubRepoInfo getRepo(String owner, String name) {
        RepoJson json;
        try {
            json = restClient.get()
                    .uri("/repos/{owner}/{name}", owner, name)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> handleError(response))
                    .body(RepoJson.class);
        } catch (ResourceAccessException e) {
            throw new GitHubApiException("Could not reach GitHub", e);
        }
        if (json == null) {
            throw new GitHubApiException("Empty response from GitHub");
        }
        if (json.isPrivate()) {
            throw new BadRequestException("Private repositories are not supported");
        }
        if (json.size() > MAX_REPO_SIZE_KB) {
            throw new BadRequestException("Repository is too large to index (limit is 50 MB of code)");
        }
        return new GitHubRepoInfo(
                json.owner().login(),
                json.name(),
                json.description(),
                json.language(),
                json.stars(),
                json.defaultBranch(),
                json.isPrivate(),
                json.size()
        );
    }

    public String getLatestCommitSha(String owner, String name, String branch) {
        String sha;
        try {
            // the "sha" media type makes GitHub return only the 40-char sha as plain text
            sha = restClient.get()
                    .uri("/repos/{owner}/{name}/commits/{branch}", owner, name, branch)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github.sha")
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> handleError(response))
                    .body(String.class);
        } catch (ResourceAccessException e) {
            throw new GitHubApiException("Could not reach GitHub", e);
        }
        if (sha == null || sha.isBlank()) {
            throw new GitHubApiException("GitHub did not return a commit sha");
        }
        return sha.trim();
    }

    // Files that differ between two commits. GitHub lists at most 300 files here.
    public CommitComparison compareCommits(String owner, String name, String baseSha, String headSha) {
        CompareJson json;
        try {
            // per_page=1 keeps the commit list small; the file list is always on the first page
            json = restClient.get()
                    .uri("/repos/{owner}/{name}/compare/{base}...{head}?per_page=1", owner, name, baseSha, headSha)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> handleError(response))
                    .body(CompareJson.class);
        } catch (ResourceAccessException e) {
            throw new GitHubApiException("Could not reach GitHub", e);
        }
        if (json == null) {
            throw new GitHubApiException("Empty response from GitHub");
        }

        List<String> changed = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<FileJson> files = json.files() == null ? List.of() : json.files();
        for (FileJson file : files) {
            switch (file.status()) {
                case "removed" -> removed.add(file.filename());
                case "renamed" -> {
                    removed.add(file.previousFilename());
                    changed.add(file.filename());
                }
                case "unchanged" -> {
                }
                default -> changed.add(file.filename());
            }
        }
        return new CommitComparison(json.status(), changed, removed, files.size());
    }

    private void handleError(ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        HttpHeaders headers = response.getHeaders();

        if (status == 404) {
            throw new NotFoundException("Repository not found or not public");
        }
        if (status == 409) {
            // GitHub answers 409 for repositories with no commits
            throw new BadRequestException("Repository is empty");
        }
        boolean rateLimited = status == 429
                || (status == 403 && ("0".equals(headers.getFirst("x-ratelimit-remaining"))
                        || headers.getFirst("retry-after") != null));
        if (rateLimited) {
            throw new GitHubApiException("GitHub rate limit reached, please try again later");
        }
        if (status == 401) {
            throw new GitHubApiException("GitHub rejected the configured token");
        }
        throw new GitHubApiException("GitHub request failed with status " + status);
    }

    // only the fields we need from GET /repos/{owner}/{name}
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RepoJson(
            String name,
            OwnerJson owner,
            String description,
            String language,
            @JsonProperty("stargazers_count") int stars,
            @JsonProperty("default_branch") String defaultBranch,
            @JsonProperty("private") boolean isPrivate,
            long size
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OwnerJson(String login) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CompareJson(String status, List<FileJson> files) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FileJson(String filename, String status, @JsonProperty("previous_filename") String previousFilename) {
    }
}
