package com.repocortex.repo.dto;

import jakarta.validation.constraints.NotBlank;

public record SubmitRepoRequest(@NotBlank(message = "url is required") String url) {
}
