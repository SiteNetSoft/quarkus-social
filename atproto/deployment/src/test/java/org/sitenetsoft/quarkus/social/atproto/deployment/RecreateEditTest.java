package org.sitenetsoft.quarkus.social.atproto.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sitenetsoft.quarkus.social.Network.ATPROTO;
import static org.sitenetsoft.quarkus.social.atproto.deployment.AtprotoApps.ANN;
import static org.sitenetsoft.quarkus.social.test.SocialAssertions.assertPublished;

import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.atproto.syntax.AtUri;
import org.sitenetsoft.quarkus.atproto.test.FakeAccount;
import org.sitenetsoft.quarkus.atproto.test.FakeAtproto;
import org.sitenetsoft.quarkus.social.NetworkResult;
import org.sitenetsoft.quarkus.social.Post;
import org.sitenetsoft.quarkus.social.Social;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.test.QuarkusExtensionTest;

/** {@code quarkus.social.atproto.edit-post=recreate}: an edit replaces the Bluesky post. */
public class RecreateEditTest {

    static final String HANDLE = "alice.bsky.social";
    static final int[] PORTS = FakeAtproto.reservePorts(RecreateEditTest.class.getName() + ".ports");
    static final FakeAtproto FAKE = FakeAtproto.start(PORTS[0], PORTS[1], PORTS[2]);
    static final FakeAccount ALICE = FAKE.account(HANDLE);

    @RegisterExtension
    static final QuarkusExtensionTest app = AtprotoApps.linkMode(FAKE, List.of(HANDLE))
            .overrideConfigKey("quarkus.social.atproto.edit-post", "recreate");

    @Inject
    Social social;

    @Test
    void updateRecreateReplacesPost() {
        AtprotoApps.link(ANN, HANDLE);
        Post post = AtprotoApps.article("recreate-1").build();
        NetworkResult.Published published = assertPublished(social.publish(ANN, post), ATPROTO);
        AtUri oldPost = AtUri.parse(published.refs().get("post.uri"));
        AtUri docUri = AtUri.parse(published.refs().get("document.uri"));

        NetworkResult.Published updated = assertPublished(social.update(ANN, AtprotoApps.article(post.id())
                .title("Edited title").build()), ATPROTO);
        AtUri newPost = AtUri.parse(updated.refs().get("post.uri"));
        assertNotEquals(oldPost, newPost);
        assertNotEquals(oldPost.rkey(), newPost.rkey());
        assertEquals(Optional.empty(), updated.note());
        assertTrue(updated.url().orElseThrow().toString().endsWith("/post/" + newPost.rkey().orElseThrow().value()));

        assertFalse(FAKE.pds().records().containsKey(oldPost), "the old post is deleted");
        JsonNode bsky = FAKE.pds().records().get(newPost);
        assertTrue(bsky.path("text").asText().startsWith("Edited title\n\n"), bsky::toString);
        JsonNode doc = FAKE.pds().records().get(docUri);
        assertEquals("Edited title", doc.path("title").asText());
        assertEquals(newPost.toString(), doc.path("bskyPostRef").path("uri").asText());
        assertEquals(updated.refs().get("post.cid"), doc.path("bskyPostRef").path("cid").asText());
        assertEquals(2, FAKE.pds().records().size());

        // the next edit replaces the new post
        NetworkResult.Published again = assertPublished(social.update(ANN, AtprotoApps.article(post.id())
                .title("Edited again").build()), ATPROTO);
        assertFalse(FAKE.pds().records().containsKey(newPost));
        assertEquals(2, FAKE.pds().records().size());
        assertNotEquals(newPost.toString(), again.refs().get("post.uri"));
    }
}
