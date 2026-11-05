package org.sitenetsoft.quarkus.social.events;

import java.util.Objects;

import org.sitenetsoft.quarkus.social.Network;

/** CDI event: someone followed (or stopped following) an app account on a network. */
public record SocialFollow(String account, Network network, SocialAuthor author, State state) {

    /** {@code ACCEPTED}/{@code PENDING}/{@code REJECTED} as the network adapter decided; {@code UNDONE}: unfollowed. */
    public enum State {
        ACCEPTED, PENDING, REJECTED, UNDONE
    }

    public SocialFollow {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(state, "state");
    }
}
