package org.sitenetsoft.quarkus.social.atproto.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.social.devui.SocialDevUiContribution;
import org.sitenetsoft.quarkus.social.devui.SocialJsonRpcService;
import org.sitenetsoft.quarkus.social.engine.PublishEngine;
import org.sitenetsoft.quarkus.social.spi.SocialAdapter;

import io.quarkus.arc.All;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.test.QuarkusExtensionTest;

/** The Bluesky scope reaches the Dev UI card through the neutral contribution SPI, with the core knowing no adapter. */
public class DevUiContributionTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(AtprotoApps.class, AtprotoApps.NoDns.class))
            .overrideConfigKey("quarkus.atproto.oauth.mode", "link")
            .overrideConfigKey("quarkus.atproto.oauth.scope", "atproto repo:app.bsky.feed.post?action=create")
            .overrideConfigKey("quarkus.social.atproto.output", "post");

    @Inject
    PublishEngine engine;
    @Inject
    @All
    List<SocialAdapter> adapters;
    @Inject
    @All
    List<SocialDevUiContribution> contributions;

    @Test
    void scopeNeededAgainstConfigured() {
        SocialJsonRpcService dev = new SocialJsonRpcService(engine, adapters, contributions, LaunchMode.DEVELOPMENT);
        assertEquals(List.of("atproto"), dev.getAdapters().stream().map(r -> r.get("network")).toList());
        List<Map<String, Object>> blocks = dev.getContributions();
        assertEquals(1, blocks.size());
        assertEquals("Bluesky OAuth scope", blocks.get(0).get("title"));
        Map<?, ?> data = (Map<?, ?>) blocks.get(0).get("data");
        assertEquals("link", data.get("mode (must be link)"));
        assertEquals("post", data.get("output"));
        assertEquals("repo:app.bsky.feed.post?action=create&action=delete", data.get("scope needed"));
        assertEquals("atproto repo:app.bsky.feed.post?action=create", data.get("scope configured"));
        assertEquals(false, data.get("configured scope covers it"));
    }
}
