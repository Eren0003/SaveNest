package com.savenest.metadata;

/** Categorized fetch failure. The category (never the URL) is stored as the bookmark's error code. */
public class FetchException extends Exception {

    public enum Category {
        BLOCKED_TARGET(false, true),     // SSRF policy rejected the destination
        ACCESS_DENIED(false, true),      // 401/403/451: site blocks automated access, not bypassed
        RATE_LIMITED(true, false),       // 429
        NOT_FOUND(false, false),
        HTTP_ERROR(false, false),        // other 4xx
        SERVER_ERROR(true, false),       // 5xx
        TOO_LARGE(false, false),
        UNSUPPORTED_TYPE(false, false),
        TOO_MANY_REDIRECTS(false, false),
        INVALID_URL(false, false),
        TIMEOUT(true, false),
        NETWORK(true, false);

        private final boolean transientFailure;
        private final boolean blocked;

        Category(boolean transientFailure, boolean blocked) {
            this.transientFailure = transientFailure;
            this.blocked = blocked;
        }

        public boolean isTransient() { return transientFailure; }
        public boolean isBlocked() { return blocked; }
        public String code() { return name().toLowerCase(); }
    }

    private final Category category;

    public FetchException(Category category, String message) {
        super(message);
        this.category = category;
    }

    public FetchException(Category category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    public Category category() { return category; }
}
