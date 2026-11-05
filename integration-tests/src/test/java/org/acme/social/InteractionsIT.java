package org.acme.social;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sitenetsoft.activitystreams.Audience;
import org.sitenetsoft.activitystreams.Create;
import org.sitenetsoft.activitystreams.Note;
import org.sitenetsoft.activitystreams.ObjectOrLink;
import org.sitenetsoft.httpsignatures.Scheme;
import org.sitenetsoft.quarkus.activitypub.test.FakeActor;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

/** A reply from the fake Mastodon to a published post shows up in the sample's {@code GET /interactions}. */
@QuarkusIntegrationTest
@WithTestResource(SocialFakes.class)
class InteractionsIT {

    final Browser ann = Browser.ann();

    @BeforeEach
    void linkedAndFollowed() {
        SocialFakes.ready();
    }

    @Test
    void mastodonReplyAppears() {
        String id = PublishBothIT.newId();
        JsonNode published = ann.postJson("/publish", Map.of("id", id, "text", "Replies welcome " + id));
        URI object = URI.create(published.at("/results/activitypub/refs/object").asText());

        FakeActor rita = SocialFakes.fediverse().actor("rita");
        Note reply = new Note();
        reply.id(URI.create(SocialFakes.fediverse().baseUri() + "/objects/" + UUID.randomUUID()));
        reply.attributedTo(ObjectOrLink.of(rita.id()));
        reply.inReplyTo(ObjectOrLink.of(object));
        reply.content("<p>Nice post!</p><script>alert(1)</script>");
        reply.to(ObjectOrLink.of(Audience.PUBLIC));
        reply.cc(ObjectOrLink.of(URI.create(SocialFakes.app() + "/users/ann")));
        Create create = new Create();
        create.id(URI.create(SocialFakes.fediverse().baseUri() + "/activities/" + UUID.randomUUID()));
        create.actor(ObjectOrLink.of(rita.id())).object(ObjectOrLink.of(reply));
        create.to(ObjectOrLink.of(Audience.PUBLIC));
        assertEquals(202, rita.post(URI.create(SocialFakes.app() + "/users/ann/inbox"), create, Scheme.CAVAGE)
                .statusCode());

        JsonNode seen = await().atMost(SocialFakes.WAIT).until(() -> replyTo(id), Optional::isPresent).orElseThrow();
        assertEquals("activitypub", seen.path("network").asText(), seen::toString);
        assertEquals(rita.id().toString(), seen.path("author").path("id").asText(), seen::toString);
        assertEquals("PUBLIC", seen.path("audience").asText(), seen::toString);
        String html = seen.path("html").asText();
        assertTrue(html.contains("Nice post!"), html);
        assertFalse(html.contains("<script") || html.contains("alert(1)"), html);

        assertEquals(401, Browser.anonymous().get("/interactions").statusCode());
    }

    private Optional<JsonNode> replyTo(String postId) {
        for (JsonNode e : ann.getJson("/interactions")) {
            if ("reply".equals(e.path("type").asText()) && postId.equals(e.path("postId").asText())) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }
}
