package com.repocortex.github;

import com.repocortex.common.BadRequestException;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class GitHubUrlParser {

    private static final String INVALID_MESSAGE =
            "Not a valid GitHub repository. Use https://github.com/owner/repo or owner/repo";

    // https://github.com/..., http://www.github.com/..., github.com/...
    private static final Pattern HTTP_URL =
            Pattern.compile("^(?:https?://)?(?:www\\.)?github\\.com/(.*)$", Pattern.CASE_INSENSITIVE);
    // git@github.com:owner/repo.git
    private static final Pattern SSH_URL =
            Pattern.compile("^git@github\\.com:(.*)$", Pattern.CASE_INSENSITIVE);

    // GitHub usernames: letters, digits and single hyphens, max 39 chars, no leading/trailing hyphen
    private static final Pattern OWNER = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}$");
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9._-]{1,100}$");

    public RepoCoordinates parse(String input) {
        if (input == null || input.isBlank()) {
            throw new BadRequestException("Repository URL is required");
        }
        String value = input.trim();

        List<String> segments;
        Matcher http = HTTP_URL.matcher(value);
        Matcher ssh = SSH_URL.matcher(value);
        if (http.matches()) {
            // extra paths like /tree/main/src are allowed, only owner and repo matter
            segments = splitPath(http.group(1));
            if (segments.size() < 2) {
                throw new BadRequestException(INVALID_MESSAGE);
            }
        } else if (ssh.matches()) {
            segments = splitPath(ssh.group(1));
            if (segments.size() != 2) {
                throw new BadRequestException(INVALID_MESSAGE);
            }
        } else if (value.contains("://") || value.contains("@")) {
            throw new BadRequestException("Only github.com repositories are supported");
        } else {
            // plain "owner/repo"
            segments = splitPath(value);
            if (segments.size() != 2) {
                throw new BadRequestException(INVALID_MESSAGE);
            }
        }

        String owner = segments.get(0);
        String name = stripGitSuffix(segments.get(1));
        if (!OWNER.matcher(owner).matches()) {
            throw new BadRequestException("Invalid GitHub owner: " + owner);
        }
        if (!NAME.matcher(name).matches() || name.equals(".") || name.equals("..")) {
            throw new BadRequestException("Invalid GitHub repository name: " + name);
        }
        return new RepoCoordinates(owner, name);
    }

    private static List<String> splitPath(String path) {
        // drop ?query and #fragment, then ignore empty parts from double or trailing slashes
        String clean = path.split("[?#]", 2)[0];
        return Arrays.stream(clean.split("/"))
                .filter(part -> !part.isEmpty())
                .toList();
    }

    private static String stripGitSuffix(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".git") ? name.substring(0, name.length() - 4) : name;
    }
}
