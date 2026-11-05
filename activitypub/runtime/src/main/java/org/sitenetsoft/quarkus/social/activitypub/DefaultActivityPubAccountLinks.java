package org.sitenetsoft.quarkus.social.activitypub;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.sitenetsoft.quarkus.activitypub.actor.LocalActor;
import org.sitenetsoft.quarkus.activitypub.actor.LocalActors;

/**
 * The adapter's own account link, used unless the app provides a {@code SocialAccountLinks} (or its reactive twin):
 * the app account id is the ActivityPub username when a local actor of that name exists. Blocking (it reads the
 * actor store).
 */
@ApplicationScoped
public class DefaultActivityPubAccountLinks {

    @Inject
    LocalActors actors;

    /** The username {@code account} publishes as: the account id itself, when that local actor exists. */
    public Optional<String> activityPubUsername(String account) {
        return actors.find(account).map(LocalActor::username);
    }

    /** The app account of a local actor: its username. */
    public Optional<String> accountForActivityPubUsername(String username) {
        return Optional.of(username);
    }
}
