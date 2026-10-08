package com.repocortex.github;

import java.util.Locale;

public record RepoCoordinates(String owner, String name) {

    public String fullName() {
        return (owner + "/" + name).toLowerCase(Locale.ROOT);
    }
}
