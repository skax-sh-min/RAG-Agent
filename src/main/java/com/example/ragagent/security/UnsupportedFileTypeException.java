package com.example.ragagent.security;

/**
 * Thrown when an uploaded file's extension or magic bytes are not supported.
 * Mapped to HTTP 422 by GlobalExceptionHandler.
 *
 * Will be absorbed into the domain exception hierarchy when 11-domain-exceptions.md is implemented.
 */
public class UnsupportedFileTypeException extends RuntimeException {
    public UnsupportedFileTypeException(String message) {
        super(message);
    }
}
