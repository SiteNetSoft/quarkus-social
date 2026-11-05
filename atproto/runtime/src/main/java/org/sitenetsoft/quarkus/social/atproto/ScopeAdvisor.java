package org.sitenetsoft.quarkus.social.atproto;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;
import org.sitenetsoft.quarkus.atproto.oauth.OAuthConfig;
import org.sitenetsoft.quarkus.atproto.oauth.auth.ScopeCheck;

import io.quarkus.runtime.StartupEvent;

/**
 * The OAuth scope the adapter's settings need, compared at startup with {@code quarkus.atproto.oauth.scope} (same
 * rules as the write checks: {@code transition:generic} covers everything): one WARN naming the scope to configure
 * when it isn't covered. A write the granted scope doesn't cover is {@code Failed(SCOPE_MISSING)}.
 */
@ApplicationScoped
public class ScopeAdvisor {

    static final String POST_SCOPE = "repo:" + AtprotoRefs.POST_COLLECTION + "?action=create&action=delete";
    static final String DOCUMENT_SCOPE = "repo:" + AtprotoRefs.DOCUMENT_COLLECTION
            + "?action=create&action=update&action=delete";

    private static final Logger LOG = Logger.getLogger(ScopeAdvisor.class);

    @Inject
    AtprotoSocialConfig config;
    @Inject
    OAuthConfig oauth;

    void check(@Observes StartupEvent event) {
        if (config.enabled() && oauth.mode() == OAuthConfig.Mode.LINK) {
            warning(oauth.scope(), config).ifPresent(LOG::warn);
        }
    }

    /** The space-separated scope {@code cfg}'s output writes with (edits recreating the post need nothing more). */
    public static String requiredScope(AtprotoSocialConfig cfg) {
        return switch (cfg.output()) {
            case POST_AND_DOCUMENT -> POST_SCOPE + " " + DOCUMENT_SCOPE;
            case POST -> POST_SCOPE;
            case DOCUMENT -> DOCUMENT_SCOPE;
        };
    }

    /** The advice to log when {@code configured} doesn't cover {@link #requiredScope}; empty when it does. */
    public static Optional<String> warning(String configured, AtprotoSocialConfig cfg) {
        List<String[]> needed = new ArrayList<>();
        if (cfg.output() != AtprotoSocialConfig.Output.DOCUMENT) {
            for (String action : List.of("create", "delete")) {
                needed.add(new String[] {AtprotoRefs.POST_COLLECTION, action});
            }
        }
        if (cfg.output() != AtprotoSocialConfig.Output.POST) {
            for (String action : List.of("create", "update", "delete")) {
                needed.add(new String[] {AtprotoRefs.DOCUMENT_COLLECTION, action});
            }
        }
        boolean covered = needed.stream()
                .allMatch(n -> ScopeCheck.allows(configured, "repo", n[0], Optional.of(n[1])));
        return covered ? Optional.empty()
                : Optional.of("quarkus-social publishes to Bluesky with the scope '" + requiredScope(cfg)
                        + "'; add it to quarkus.atproto.oauth.scope (now '" + configured
                        + "'), or the writes fail with SCOPE_MISSING");
    }
}
