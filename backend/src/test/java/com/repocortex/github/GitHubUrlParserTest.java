package com.repocortex.github;

import com.repocortex.common.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GitHubUrlParserTest {

    private final GitHubUrlParser parser = new GitHubUrlParser();

    @ParameterizedTest
    @ValueSource(strings = {
            "https://github.com/spring-projects/spring-petclinic",
            "https://github.com/spring-projects/spring-petclinic.git",
            "https://github.com/spring-projects/spring-petclinic/",
            "https://github.com/spring-projects/spring-petclinic.git/",
            "https://github.com/spring-projects/spring-petclinic/tree/main/src",
            "https://github.com/spring-projects/spring-petclinic/blob/main/pom.xml#L10-L20",
            "https://github.com/spring-projects/spring-petclinic?tab=readme",
            "http://github.com/spring-projects/spring-petclinic",
            "https://www.github.com/spring-projects/spring-petclinic",
            "http://www.github.com/spring-projects/spring-petclinic",
            "github.com/spring-projects/spring-petclinic",
            "HTTPS://GitHub.com/spring-projects/spring-petclinic",
            "git@github.com:spring-projects/spring-petclinic.git",
            "git@github.com:spring-projects/spring-petclinic",
            "spring-projects/spring-petclinic",
            "  spring-projects/spring-petclinic  ",
    })
    void parsesSupportedForms(String input) {
        RepoCoordinates coords = parser.parse(input);

        assertEquals("spring-projects", coords.owner());
        assertEquals("spring-petclinic", coords.name());
        assertEquals("spring-projects/spring-petclinic", coords.fullName());
    }

    @Test
    void fullNameIsLowercaseButOriginalCaseIsKept() {
        RepoCoordinates coords = parser.parse("https://github.com/Spring-Projects/Spring-PetClinic");

        assertEquals("Spring-Projects", coords.owner());
        assertEquals("Spring-PetClinic", coords.name());
        assertEquals("spring-projects/spring-petclinic", coords.fullName());
    }

    @Test
    void allowsDotsInRepoName() {
        RepoCoordinates coords = parser.parse("https://github.com/vercel/next.js");

        assertEquals("next.js", coords.name());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "not-a-url",
            "https://github.com",
            "https://github.com/",
            "https://github.com/spring-projects",
            "https://gitlab.com/spring-projects/spring-petclinic",
            "https://example.com/github.com/owner/repo",
            "git@gitlab.com:owner/repo.git",
            "owner/repo/extra",
            "-owner/repo",
            "owner-/repo",
            "own--er/repo",
            "own_er/repo",
            "owner/re po",
            "owner/repo!",
            "owner/..",
            "https://github.com/owner/.git",
    })
    void rejectsInvalidInput(String input) {
        assertThrows(BadRequestException.class, () -> parser.parse(input));
    }

    @Test
    void rejectsNull() {
        assertThrows(BadRequestException.class, () -> parser.parse(null));
    }
}
