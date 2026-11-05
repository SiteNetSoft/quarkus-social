package org.sitenetsoft.quarkus.social;

/** A caller error reported by the social API (not a per-network failure, which is a {@link NetworkResult}). */
public class SocialException extends RuntimeException {

    public SocialException(String message) {
        super(message);
    }

    public SocialException(String message, Throwable cause) {
        super(message, cause);
    }
}
