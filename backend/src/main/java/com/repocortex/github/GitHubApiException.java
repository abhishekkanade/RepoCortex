package com.repocortex.github;

// GitHub itself failed or refused us (rate limit, bad token, outage)
public class GitHubApiException extends RuntimeException {

    public GitHubApiException(String message) {
        super(message);
    }

    public GitHubApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
