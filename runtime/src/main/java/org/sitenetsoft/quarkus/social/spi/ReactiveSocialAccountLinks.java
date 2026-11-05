package org.sitenetsoft.quarkus.social.spi;

import java.util.Optional;

import io.smallrye.mutiny.Uni;

/** Reactive form of {@link SocialAccountLinks}; adapters prefer it when the app provides both. */
public interface ReactiveSocialAccountLinks {

    Uni<Optional<String>> activityPubUsername(String account);

    Uni<Optional<String>> atprotoDid(String account);

    Uni<Optional<String>> accountForActivityPubUsername(String username);

    Uni<Optional<String>> accountForAtprotoDid(String did);
}
