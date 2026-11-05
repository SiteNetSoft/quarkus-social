package org.sitenetsoft.quarkus.social.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.social.Network;
import org.sitenetsoft.quarkus.social.devui.SocialDevUiContribution;
import org.sitenetsoft.quarkus.social.devui.SocialJsonRpcService;
import org.sitenetsoft.quarkus.social.engine.PublishEngine;
import org.sitenetsoft.quarkus.social.spi.SocialAdapter;
import org.sitenetsoft.quarkus.social.test.RecordingSocialAdapter;

import io.quarkus.arc.All;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.test.QuarkusExtensionTest;

/** The Dev UI card's backend, in an app with a Mastodon-like and a Bluesky-like network. */
public class DevUiTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Fediverse.class, Bluesky.class, Scope.class));

    @Inject
    PublishEngine engine;
    @Inject
    Bluesky bluesky;
    @Inject
    @All
    List<SocialAdapter> adapters;
    @Inject
    @All
    List<SocialDevUiContribution> contributions;

    private SocialJsonRpcService dev() {
        return new SocialJsonRpcService(engine, adapters, contributions, LaunchMode.DEVELOPMENT);
    }

    @Test
    void adaptersAndContributions() {
        bluesky.unavailable("not linked here");
        List<Map<String, Object>> rows;
        try {
            rows = dev().getAdapters();
        } finally {
            bluesky.reset();
        }
        assertEquals(List.of("activitypub", "atproto"), rows.stream().map(r -> r.get("network")).toList());
        Map<?, ?> caps = assertInstanceOf(Map.class, rows.get(0).get("capabilities"));
        assertEquals(List.of("FOLLOWERS", "PUBLIC", "UNLISTED"), caps.get("visibilities"));
        assertEquals(true, caps.get("edits"));
        assertEquals("not linked here", rows.get(1).get("unavailableReason"));
        assertEquals(null, rows.get(0).get("unavailableReason"));

        List<Map<String, Object>> blocks = dev().getContributions();
        assertEquals(1, blocks.size());
        assertEquals("Test scope", blocks.get(0).get("title"));
        assertEquals(Map.of("needed", "a b"), blocks.get(0).get("data"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishTestPostThenRecent() {
        Map<String, Object> out = dev().publishTestPost("alice", "Hello from the Dev UI", "NOTE", "UNLISTED")
                .await().indefinitely();
        String postId = (String) out.get("postId");
        assertTrue(postId.startsWith("dev-ui-test-"), postId);
        Map<String, Object> results = (Map<String, Object>) out.get("results");
        assertEquals("published", ((Map<String, Object>) results.get("activitypub")).get("outcome"));
        assertEquals("published", ((Map<String, Object>) results.get("atproto")).get("outcome"));

        List<Map<String, Object>> recent = dev().getRecent().await().indefinitely();
        Map<String, Object> mine = recent.stream().filter(p -> postId.equals(p.get("postId"))).findFirst().orElseThrow();
        assertEquals("alice", mine.get("account"));
        assertEquals("UNLISTED", mine.get("visibility"));
        assertEquals("Hello from the Dev UI", mine.get("excerpt"));
        List<Map<String, Object>> states = (List<Map<String, Object>>) mine.get("networks");
        assertEquals(List.of("activitypub", "atproto"), states.stream().map(s -> s.get("network")).toList());
        assertEquals("PUBLISHED", states.get(0).get("status"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void articleGetsTitleAndUrl() {
        Map<String, Object> out = dev().publishTestPost("alice", "A title\nbody", "article", null)
                .await().indefinitely();
        assertEquals("published", ((Map<String, Object>) ((Map<String, Object>) out.get("results"))
                .get("activitypub")).get("outcome"));
    }

    @Test
    void recentIsCappedAtTwenty() {
        for (int i = 0; i < 22; i++) {
            dev().publishTestPost("alice", "n" + i, null, null).await().indefinitely();
        }
        assertEquals(20, dev().getRecent().await().indefinitely().size());
    }

    @Test
    void badInputIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> dev().publishTestPost("alice", "x", "NOTE", "SECRET").await().indefinitely());
        assertThrows(IllegalArgumentException.class,
                () -> dev().publishTestPost("alice", " ", null, null).await().indefinitely());
        assertThrows(IllegalArgumentException.class,
                () -> dev().publishTestPost(" ", "x", null, null).await().indefinitely());
    }

    @Test
    void publishTestPostRefusesOutsideDevMode() {
        for (LaunchMode mode : List.of(LaunchMode.NORMAL, LaunchMode.TEST)) {
            SocialJsonRpcService service = new SocialJsonRpcService(engine, adapters, contributions, mode);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> service.publishTestPost("alice", "Hello", null, null).await().indefinitely());
            assertTrue(e.getMessage().contains("dev mode only"), e.getMessage());
        }
        // the default constructor reads the real launch mode: this test app runs in TEST mode
        SocialJsonRpcService real = new SocialJsonRpcService(engine, adapters, contributions);
        assertThrows(IllegalStateException.class,
                () -> real.publishTestPost("alice", "Hello", null, null).await().indefinitely());
        assertTrue(engine.recent(100).await().indefinitely().stream()
                .noneMatch(p -> p.postId().startsWith("dev-ui-test-") && p.snapshot().text().orElse("").equals("Hello")));
        assertFalse(adapters.isEmpty());
    }

    @Singleton
    public static class Fediverse extends RecordingSocialAdapter {
        public Fediverse() {
            super(Network.ACTIVITYPUB);
        }
    }

    @Singleton
    public static class Bluesky extends RecordingSocialAdapter {
        public Bluesky() {
            super(Network.ATPROTO);
        }
    }

    @Singleton
    public static class Scope implements SocialDevUiContribution {
        @Override
        public String title() {
            return "Test scope";
        }

        @Override
        public Map<String, Object> data() {
            return Map.of("needed", "a b");
        }
    }
}
