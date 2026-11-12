package com.savenest.classifier;

/** Thrown when input cannot be accepted as a bookmark URL. {@link #code()} is stable for API clients. */
public class InvalidUrlException extends RuntimeException {

    public enum Code { INVALID_URL, UNSUPPORTED_SCHEME, CREDENTIALS_NOT_ALLOWED, URL_TOO_LONG }

    private final Code code;

    public InvalidUrlException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() { return code; }
}
