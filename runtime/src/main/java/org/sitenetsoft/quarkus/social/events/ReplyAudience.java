package org.sitenetsoft.quarkus.social.events;

/** Who an inbound reply was addressed to (derived from the network's addressing). */
public enum ReplyAudience {
    /** Addressed to everyone. */
    PUBLIC,
    /** Public but not meant for public timelines. */
    UNLISTED,
    /** Addressed to the replier's followers only. */
    FOLLOWERS_ONLY,
    /** Addressed to named recipients only. */
    DIRECT
}
