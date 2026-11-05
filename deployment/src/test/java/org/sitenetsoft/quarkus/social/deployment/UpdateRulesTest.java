package org.sitenetsoft.quarkus.social.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sitenetsoft.quarkus.social.deployment.TestAdapters.A;
import static org.sitenetsoft.quarkus.social.deployment.TestAdapters.B;
import static org.sitenetsoft.quarkus.social.test.SocialAssertions.assertFailed;
import static org.sitenetsoft.quarkus.social.test.SocialAssertions.assertPublished;
import static org.sitenetsoft.quarkus.social.test.SocialAssertions.assertSkipped;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.social.FailureCode;
import org.sitenetsoft.quarkus.social.Kind;
import org.sitenetsoft.quarkus.social.Post;
import org.sitenetsoft.quarkus.social.PublishResult;
import org.sitenetsoft.quarkus.social.SkipReason;
import org.sitenetsoft.quarkus.social.Social;
import org.sitenetsoft.quarkus.social.SocialException;
import org.sitenetsoft.quarkus.social.Visibility;
import org.sitenetsoft.quarkus.social.store.NetworkState;
import org.sitenetsoft.quarkus.social.store.StoredPost;
import org.sitenetsoft.quarkus.social.test.RecordingSocialAdapter.Operation;
import org.sitenetsoft.quarkus.social.test.RecordingSocialAdapter.RecordedRequest;

import io.quarkus.test.QuarkusExtensionTest;

/** "Edits that can't be carried" and delete. */
public class UpdateRulesTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(TestAdapters.class, TestAdapters.AdapterA.class,
                    TestAdapters.AdapterB.class, TestAdapters.ProgressEvents.class));

    @Inject
    Social social;
    @Inject
    TestAdapters.AdapterA a;
    @Inject
    TestAdapters.AdapterB b;

    @BeforeEach
    void reset() {
        a.reset();
        b.reset();
    }

    static String newId() {
        return "post-" + UUID.randomUUID();
    }

    static Post note(String id, String text) {
        return Post.builder(id).text(text).build();
    }

    @Test
    void updateRefusesVisibilityChange() {
        String id = newId();
        social.publish("alice", note(id, "Hello"));

        Post narrowed = Post.builder(id).text("Hello").visibility(Visibility.FOLLOWERS).build();
        SocialException e = assertThrows(SocialException.class, () -> social.update("alice", narrowed));
        assertTrue(e.getMessage().contains("visibility"), e.getMessage());
        assertTrue(e.getMessage().contains("delete it and publish a new post"), e.getMessage());
        assertEquals(1, a.requests().size());
        assertEquals(1, b.requests().size());
        assertEquals(Visibility.PUBLIC, social.status(id).orElseThrow().snapshot().visibility());
    }

    @Test
    void updateRefusesKindChange() {
        String id = newId();
        social.publish("alice", note(id, "Hello"));

        Post article = Post.builder(id).kind(Kind.ARTICLE).title("Hello").url(URI.create("https://blog.example/hello"))
                .text("Hello").build();
        SocialException e = assertThrows(SocialException.class, () -> social.update("alice", article));
        assertTrue(e.getMessage().contains("kind"), e.getMessage());
        assertTrue(e.getMessage().contains("delete it and publish a new post"), e.getMessage());
        assertEquals(1, a.requests().size());
    }

    @Test
    void updateSkipsNetworksWithoutPost() {
        String id = newId();
        a.failNext(FailureCode.UNAVAILABLE, true);
        social.publish("alice", note(id, "Hello"));

        PublishResult r = social.update("alice", note(id, "Edited"));

        assertSkipped(r, A, SkipReason.NOT_PUBLISHED_HERE);
        assertEquals(1, a.requests().size());
        assertPublished(r, B);
        assertEquals(2, b.requests().size());
        RecordedRequest update = b.requests().get(1);
        assertEquals(Operation.UPDATE, update.operation());
        assertEquals("Edited", update.request().content().text());
        assertEquals(NetworkState.Status.PUBLISHED, update.request().previous().orElseThrow().status());

        StoredPost stored = social.status(id).orElseThrow();
        assertEquals("Edited", stored.snapshot().text().orElseThrow());
        assertEquals(NetworkState.Status.FAILED, stored.networks().get(A).status());
        assertEquals(NetworkState.Status.PUBLISHED, stored.networks().get(B).status());
    }

    @Test
    void failedUpdateKeepsPublishedState() {
        String id = newId();
        social.publish("alice", note(id, "Hello"));
        a.failNext(FailureCode.UNAVAILABLE, true);

        PublishResult r = social.update("alice", note(id, "Edited"));

        assertFailed(r, A, FailureCode.UNAVAILABLE);
        assertEquals(NetworkState.Status.PUBLISHED, social.status(id).orElseThrow().networks().get(A).status());
        // still published there, so a second update tries it again
        social.update("alice", note(id, "Edited again"));
        assertEquals(3, a.requests().size());
    }

    @Test
    void updateUnknownPostThrows() {
        assertThrows(SocialException.class, () -> social.update("alice", note(newId(), "Hello")));
        assertTrue(a.requests().isEmpty());
    }

    @Test
    void deleteMarksDeleted() {
        String id = newId();
        b.skipNext(SkipReason.VISIBILITY_NOT_SUPPORTED);
        social.publish("alice", note(id, "Hello"));

        PublishResult r = social.delete("alice", id);

        assertPublished(r, A);
        assertSkipped(r, B, SkipReason.NOT_PUBLISHED_HERE);
        assertEquals(2, a.requests().size());
        RecordedRequest delete = a.requests().get(1);
        assertEquals(Operation.DELETE, delete.operation());
        assertEquals(id, delete.request().post().id());
        assertEquals("Hello", delete.request().content().text());
        assertEquals(1, b.requests().size());

        StoredPost stored = social.status(id).orElseThrow();
        assertEquals(NetworkState.Status.DELETED, stored.networks().get(A).status());
        assertEquals(NetworkState.Status.SKIPPED, stored.networks().get(B).status());
    }

    @Test
    void deleteCallsPartiallyWrittenNetworks() {
        String id = newId();
        a.failNext(FailureCode.UNAVAILABLE, true, Map.of("record", "at://example/partial"));
        social.publish("alice", note(id, "Hello"));

        PublishResult r = social.delete("alice", id);

        assertPublished(r, A);
        assertEquals(Operation.DELETE, a.requests().get(1).operation());
        assertEquals(NetworkState.Status.DELETED, social.status(id).orElseThrow().networks().get(A).status());
    }

    @Test
    void deleteUnknownPostThrows() {
        assertThrows(SocialException.class, () -> social.delete("alice", newId()));
    }

    @Test
    void anotherAccountCannotUpdateOrDelete() {
        String id = newId();
        social.publish("alice", note(id, "Hello"));

        assertThrows(SocialException.class, () -> social.update("mallory", note(id, "Mine now")));
        assertThrows(SocialException.class, () -> social.delete("mallory", id));
        assertEquals(1, a.requests().size());
    }
}
