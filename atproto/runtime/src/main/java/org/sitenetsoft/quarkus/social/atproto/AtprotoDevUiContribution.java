package org.sitenetsoft.quarkus.social.atproto;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.sitenetsoft.quarkus.atproto.oauth.OAuthConfig;
import org.sitenetsoft.quarkus.social.devui.SocialDevUiContribution;

/**
 * What the "Social" Dev UI card shows for Bluesky: the OAuth scope the settings need against the one configured. The
 * core finds this bean through {@link SocialDevUiContribution}, so it needs no dependency on this module.
 */
@ApplicationScoped
public class AtprotoDevUiContribution implements SocialDevUiContribution {

    @Inject
    AtprotoSocialConfig config;
    @Inject
    OAuthConfig oauth;

    @Override
    public String title() {
        return "Bluesky OAuth scope";
    }

    @Override
    public Map<String, Object> data() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode (must be link)", oauth.mode().name().toLowerCase(java.util.Locale.ROOT));
        m.put("output", config.output().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
        m.put("scope needed", ScopeAdvisor.requiredScope(config));
        m.put("scope configured", oauth.scope());
        m.put("configured scope covers it", ScopeAdvisor.warning(oauth.scope(), config).isEmpty());
        return m;
    }
}
