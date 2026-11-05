package org.sitenetsoft.quarkus.social;

public enum FailureCode {
    TIMEOUT,
    UNAVAILABLE,
    SESSION_EXPIRED,
    SCOPE_MISSING,
    TEXT_TOO_LONG,
    CONTENT_WARNING_NOT_SUPPORTED,
    REJECTED,
    INTERNAL
}
