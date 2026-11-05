package org.acme.social;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.sitenetsoft.quarkus.activitypub.actor.LocalActors;

import io.quarkus.runtime.Startup;

/**
 * The sample's account {@code ann}: the application user (basic auth, {@code application.properties}) is also the
 * ActivityPub actor {@code ann}, registered here at startup. quarkus-social publishes an account to ActivityPub as the
 * local actor with the same username, and to Bluesky as the account the user linked with the {@code quarkus-atproto}
 * link flow ({@code POST /atproto/login} while signed in).
 */
@Startup
@ApplicationScoped
public class DemoAccounts {

    static final String ANN = "ann";

    @Inject
    LocalActors actors;

    @PostConstruct
    void registerAnn() {
        if (actors.find(ANN).isEmpty()) {
            actors.register(ANN, a -> a.name("Ann").summary("The quarkus-social sample account"));
        }
    }
}
