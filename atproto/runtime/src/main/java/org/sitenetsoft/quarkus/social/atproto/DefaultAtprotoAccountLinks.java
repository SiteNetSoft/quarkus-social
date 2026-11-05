package org.sitenetsoft.quarkus.social.atproto;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.sitenetsoft.quarkus.atproto.oauth.link.AccountLinks;

import io.smallrye.mutiny.Uni;

/**
 * The adapter's own account link, used unless the app provides a {@code SocialAccountLinks} (or its reactive twin):
 * the single DID the app account linked in {@code quarkus-atproto}'s {@code link} mode ({@code AtprotoAccountLinks}).
 * Several linked DIDs are an error the app resolves with its own {@code SocialAccountLinks}. No reverse lookup.
 */
@ApplicationScoped
public class DefaultAtprotoAccountLinks {

    static final String SEVERAL = "several Bluesky accounts linked; provide SocialAccountLinks";

    @Inject
    AccountLinks links;

    /** The account's linked DID; empty when none; fails ({@link IllegalStateException}) when several. */
    public Uni<Optional<String>> atprotoDid(String account) {
        return links.links().findByPrincipal(account).map(dids -> {
            if (dids.size() > 1) {
                throw new IllegalStateException(SEVERAL + " (account " + account + " has " + dids.size() + ")");
            }
            return dids.stream().findFirst().map(Object::toString);
        });
    }

    /** Not offered: a reverse lookup would scan every link. Empty. */
    public Optional<String> accountForAtprotoDid(String did) {
        return Optional.empty();
    }
}
