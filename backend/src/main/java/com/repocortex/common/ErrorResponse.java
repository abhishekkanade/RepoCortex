package com.repocortex.common;

public record ErrorResponse(int status, String error, String message) {
}
