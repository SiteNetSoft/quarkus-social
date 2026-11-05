package org.acme.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sitenetsoft.atproto.syntax.AtUri;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

/** A network that is down fails alone and retryably; calling {@code publish} again sends only what is missing. */
@QuarkusIntegrationTest
@WithTestResource(SocialFakes.class)
class RetryIT {

    final Browser ann = Browser.ann();

    @BeforeEach
    void linkedAndFollowed() {
        SocialFakes.ready();
    }

    @AfterEach
    void pdsUp() {
        SocialFakes.atproto().pds().failNextWrites(503, 0);
    }

    @Test
    void blueskyDownThenRetry() {
        String id = PublishBothIT.newId();
        Map<String, String> form = Map.of("id", id, "text", "Bluesky is down once " + id);

        SocialFakes.atproto().pds().failNextWrites(503, 1);
        JsonNode first = ann.postJson("/publish", form);
        assertEquals("Published", first.at("/results/activitypub/status").asText(), first::toString);
        JsonNode failed = first.at("/results/atproto");
        assertEquals("Failed", failed.path("status").asText(), first::toString);
        assertEquals("UNAVAILABLE", failed.path("code").asText(), first::toString);
        assertTrue(failed.path("retryable").asBoolean(), first::toString);
        String object = first.at("/results/activitypub/refs/object").asText();
        SocialFakes.awaitActivity("Create", object);
        assertTrue(SocialFakes.atproto().pds().records().values().stream().noneMatch(v -> v.toString().contains(id)),
                "nothing written to Bluesky");

        // the retry: ActivityPub already has the post (its stored result, not sent again), only Bluesky is written
        JsonNode second = ann.postJson("/publish", form);
        assertEquals("Published", second.at("/results/activitypub/status").asText(), second::toString);
        assertFalse(second.at("/results/activitypub/resent").asBoolean(), second::toString);
        assertEquals(object, second.at("/results/activitypub/refs/object").asText());
        assertEquals("Published", second.at("/results/atproto/status").asText(), second::toString);
        assertTrue(second.at("/results/atproto/resent").asBoolean(), "sent in this call: " + second);
        AtUri post = AtUri.parse(second.at("/results/atproto/refs/post.uri").asText());
        assertNotNull(SocialFakes.atproto().pds().records().get(post), () -> "no post on the PDS: " + post);
        assertEquals(1, SocialFakes.activities("Create", object).size(), "the follower got the Create once");

        JsonNode status = ann.getJson("/status/" + id);
        assertEquals("PUBLISHED", status.at("/networks/activitypub/status").asText(), status::toString);
        assertEquals("PUBLISHED", status.at("/networks/atproto/status").asText(), status::toString);
    }
}
