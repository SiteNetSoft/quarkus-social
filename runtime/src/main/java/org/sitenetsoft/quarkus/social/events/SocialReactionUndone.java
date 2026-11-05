package org.sitenetsoft.quarkus.social.events;

import java.time.Instant;
import java.util.Objects;

import org.sitenetsoft.quarkus.social.Network;

/** CDI event: a like or repost was withdrawn by its author. */
public record SocialReactionUndone(String postId, String account, Network network, SocialAuthor author,
        SocialReaction.Type type, Instant receivedAt) {

    public SocialReactionUndone {
        Objects.requireNonNull(postId, "postId");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }
}
