package org.sitenetsoft.quarkus.social.events;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

import org.sitenetsoft.quarkus.social.Network;

/**
 * Who sent an inbound interaction, as far as the network told us without a further fetch.
 *
 * @param id          the author's network-wide id (an actor URL, a DID)
 * @param handle      {@code name@host} when known
 * @param displayName the display name when known
 * @param profileUrl  where a person would look at the profile
 * @param avatarUrl   the avatar image when known
 */
public record SocialAuthor(Network network, String id, Optional<String> handle, Optional<String> displayName,
        Optional<URI> profileUrl, Optional<URI> avatarUrl) {

    public SocialAuthor {
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(profileUrl, "profileUrl");
        Objects.requireNonNull(avatarUrl, "avatarUrl");
    }

    /** An author known by id only. */
    public static SocialAuthor of(Network network, String id) {
        return new SocialAuthor(network, id, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
}
