package org.sitenetsoft.quarkus.social.events;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import org.sitenetsoft.quarkus.social.Network;

/**
 * CDI event: a reply to a post published through this library arrived from a network.
 *
 * @param postId the replied-to post
 * @param html     the reply's content, sanitized ({@code Html.sanitize}): safe to render
 * @param audience who the reply was addressed to. <strong>Apps should render only {@code PUBLIC} and
 *                 {@code UNLISTED} replies publicly</strong>; {@code FOLLOWERS_ONLY} and {@code DIRECT} replies
 *                 were not meant for the world and are fired so an app can route them privately
 * @param url    where the reply lives on its network, when it says
 */
public record SocialReply(String postId, String account, Network network, SocialAuthor author, String html,
        ReplyAudience audience, Optional<URI> url, Instant receivedAt) {

    public SocialReply {
        Objects.requireNonNull(postId, "postId");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(html, "html");
        Objects.requireNonNull(audience, "audience");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }
}
