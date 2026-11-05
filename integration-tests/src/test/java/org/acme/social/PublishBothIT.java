package org.acme.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sitenetsoft.atproto.syntax.AtUri;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Milestone 4's done-criterion against the packaged sample: one {@code POST /publish} reaches the fake fediverse (the
 * follower {@code carol} receives the {@code Create}) and the fake PDS (Bluesky post and Standard.site document),
 * with a result per network.
 */
@QuarkusIntegrationTest
@WithTestResource(SocialFakes.class)
class PublishBothIT {

    static final String PUBLIC = "https://www.w3.org/ns/activitystreams#Public";

    final Browser ann = Browser.ann();

    @BeforeEach
    void linkedAndFollowed() {
        SocialFakes.ready();
    }

    @Test
    void bothAdaptersAreActive() {
        // the app depends on neither adapter: both came in as conditional dependencies of quarkus-social
        JsonNode caps = ann.getJson("/capabilities");
        assertEquals(List.of("activitypub", "atproto"), fieldNames(caps));
        assertEquals(List.of("PUBLIC"), texts(caps.path("atproto").path("visibilities")));
        assertTrue(texts(caps.path("activitypub").path("visibilities")).contains("FOLLOWERS"), caps::toString);
        assertEquals(300, caps.path("atproto").path("maxTextGraphemes").asInt());
    }

    @Test
    void oneCallReachesBoth() {
        String id = newId();
        String url = "https://blog.example/posts/" + id;
        JsonNode r = ann.postJson("/publish", Map.of("id", id, "kind", "ARTICLE", "title", "Hello world",
                "text", "A first post from the sample. #quarkus", "url", url));
        assertEquals(id, r.path("postId").asText());
        JsonNode ap = r.path("results").path("activitypub");
        JsonNode at = r.path("results").path("atproto");
        assertEquals("Published", ap.path("status").asText(), r::toString);
        assertEquals("Published", at.path("status").asText(), r::toString);

        // the follower on the fake fediverse receives the Create of a Note (article-style note-with-link)
        String object = ap.path("refs").path("object").asText();
        JsonNode create = SocialFakes.awaitActivity("Create", object);
        assertEquals("Note", create.path("object").path("type").asText(), create::toString);
        assertEquals(url, create.path("object").path("url").asText(), create::toString);
        assertTrue(create.path("object").path("content").asText().contains("Hello world"), create::toString);

        // the fake PDS holds alice's Bluesky post and the Standard.site document pointing at it
        AtUri post = AtUri.parse(at.path("refs").path("post.uri").asText());
        AtUri document = AtUri.parse(at.path("refs").path("document.uri").asText());
        assertEquals(SocialFakes.alice().did(), post.authority());
        JsonNode bsky = SocialFakes.atproto().pds().records().get(post);
        assertNotNull(bsky, () -> "no post on the PDS: " + post);
        assertEquals("app.bsky.feed.post", bsky.path("$type").asText());
        assertTrue(bsky.path("text").asText().startsWith("Hello world"), bsky::toString);
        assertEquals(url, bsky.path("embed").path("external").path("uri").asText(), bsky::toString);
        JsonNode doc = SocialFakes.atproto().pds().records().get(document);
        assertNotNull(doc, () -> "no document on the PDS: " + document);
        assertEquals("site.standard.document", doc.path("$type").asText());
        assertEquals(post.toString(), doc.path("bskyPostRef").path("uri").asText());

        JsonNode status = ann.getJson("/status/" + id);
        assertEquals("PUBLISHED", status.path("networks").path("activitypub").path("status").asText(), status::toString);
        assertEquals("PUBLISHED", status.path("networks").path("atproto").path("status").asText(), status::toString);
    }

    @Test
    void followersOnlyGoesToActivityPubOnly() {
        String id = newId();
        String text = "For followers only " + id;
        JsonNode r = ann.postJson("/publish", Map.of("id", id, "text", text, "visibility", "FOLLOWERS"));
        JsonNode ap = r.path("results").path("activitypub");
        JsonNode at = r.path("results").path("atproto");
        assertEquals("Published", ap.path("status").asText(), r::toString);
        assertEquals("Skipped", at.path("status").asText(), r::toString);
        assertEquals("VISIBILITY_NOT_SUPPORTED", at.path("reason").asText(), r::toString);

        JsonNode create = SocialFakes.awaitActivity("Create", ap.path("refs").path("object").asText());
        assertFalse(create.toString().contains(PUBLIC), () -> "addressed to Public: " + create);
        assertTrue(create.path("object").path("to").toString().contains(SocialFakes.app() + "/users/ann/followers"),
                create::toString);
        assertTrue(SocialFakes.atproto().pds().records().values().stream()
                .noneMatch(v -> v.toString().contains(id)), "nothing written to Bluesky");
    }

    @Test
    void updateAndDelete() {
        String id = newId();
        String url = "https://blog.example/posts/" + id;
        JsonNode first = ann.postJson("/publish", Map.of("id", id, "kind", "ARTICLE", "title", "Draft title",
                "text", "First version.", "url", url));
        String object = first.at("/results/activitypub/refs/object").asText();
        AtUri post = AtUri.parse(first.path("results").path("atproto").path("refs").path("post.uri").asText());
        AtUri document = AtUri.parse(first.path("results").path("atproto").path("refs").path("document.uri").asText());
        SocialFakes.awaitActivity("Create", object);

        JsonNode updated = ann.postJson("/update", Map.of("id", id, "kind", "ARTICLE", "title", "Final title",
                "text", "Second version.", "url", url));
        assertEquals("Published", updated.at("/results/activitypub/status").asText(), updated::toString);
        assertEquals("Published", updated.at("/results/atproto/status").asText(), updated::toString);
        JsonNode update = SocialFakes.awaitActivity("Update", object);
        assertTrue(update.path("object").path("content").asText().contains("Final title"), update::toString);
        assertEquals("Final title", SocialFakes.atproto().pds().records().get(document).path("title").asText());
        assertNotNull(SocialFakes.atproto().pds().records().get(post), "the Bluesky post is kept (edit-post=keep)");

        JsonNode deleted = ann.postJson("/delete", Map.of("id", id));
        assertEquals("Published", deleted.at("/results/activitypub/status").asText(), deleted::toString);
        assertEquals("Published", deleted.at("/results/atproto/status").asText(), deleted::toString);
        SocialFakes.awaitActivity("Delete", object);
        assertNull(SocialFakes.atproto().pds().records().get(post), "Bluesky post deleted");
        assertNull(SocialFakes.atproto().pds().records().get(document), "document deleted");
        JsonNode status = ann.getJson("/status/" + id);
        assertEquals("DELETED", status.at("/networks/activitypub/status").asText(), status::toString);
        assertEquals("DELETED", status.at("/networks/atproto/status").asText(), status::toString);
    }

    @Test
    void publishNeedsTheAppsSignInAndASameOriginPost() {
        Map<String, String> form = Map.of("id", newId(), "text", "Should not be sent");
        assertEquals(401, Browser.anonymous().postForm("/publish", form).statusCode());
        assertEquals(403, ann.postForm("/publish", form, Map.of("Sec-Fetch-Site", "cross-site")).statusCode());
        assertEquals(403, ann.postForm("/publish", form, Map.of("Origin", "https://evil.example")).statusCode());
        assertEquals(403, ann.postForm("/publish", form, Map.of()).statusCode(), "no Origin, no Sec-Fetch-Site");
        assertEquals(404, ann.get("/status/" + form.get("id")).statusCode(), "nothing was published");
        // a same-origin post without Sec-Fetch-Site (older browsers) is checked by its Origin
        String id = newId();
        assertEquals(200, ann.postForm("/publish", Map.of("id", id, "text", "Origin checked " + id),
                Map.of("Origin", SocialFakes.app())).statusCode());
    }

    static String newId() {
        return "post-" + UUID.randomUUID();
    }

    static List<String> fieldNames(JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        names.sort(null);
        return names;
    }

    static List<String> texts(JsonNode array) {
        List<String> out = new java.util.ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }
}
