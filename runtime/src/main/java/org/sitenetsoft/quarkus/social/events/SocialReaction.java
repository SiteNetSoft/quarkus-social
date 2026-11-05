package org.sitenetsoft.quarkus.social.events;

import java.time.Instant;
import java.util.Objects;

import org.sitenetsoft.quarkus.social.Network;

/** CDI event: a like or repost of a post published through this library arrived from a network. */
public record SocialReaction(String postId, String account, Network network, SocialAuthor author, Type type,
        Instant receivedAt) {

    public enum Type {
        LIKE, REPOST
    }

    public SocialReaction {
        Objects.requireNonNull(postId, "postId");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }
}
