package org.sitenetsoft.quarkus.social.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.sitenetsoft.quarkus.social.deployment.TestAdapters.A;
import static org.sitenetsoft.quarkus.social.deployment.TestAdapters.B;
import static org.sitenetsoft.quarkus.social.test.SocialAssertions.assertFailed;
import static org.sitenetsoft.quarkus.social.test.SocialAssertions.assertPublished;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.social.FailureCode;
import org.sitenetsoft.quarkus.social.Network;
import org.sitenetsoft.quarkus.social.NetworkResult;
import org.sitenetsoft.quarkus.social.Post;
import org.sitenetsoft.quarkus.social.PublishResult;
import org.sitenetsoft.quarkus.social.ReactiveSocial;
import org.sitenetsoft.quarkus.social.Social;
import org.sitenetsoft.quarkus.social.store.InMemorySocialPostStore;
import org.sitenetsoft.quarkus.social.store.NetworkState;
import org.sitenetsoft.quarkus.social.store.NetworkState.Status;
import org.sitenetsoft.quarkus.social.store.SocialPostStore;
import org.sitenetsoft.quarkus.social.store.StoredPost;

import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.mutiny.subscription.Cancellable;

/**
 * Another node writing the same post between this node's reads and writes (simulated by a store that interferes once),
 * leases, and a cancelled subscriber.
 */
public class StoreRacesTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(TestAdapters.class, TestAdapters.AdapterA.class,
                    TestAdapters.AdapterB.class, TestAdapters.ProgressEvents.class, RacyStore.class));

    /** The in-memory store, plus "another node" writing once right before the next replace or insert. */
    @Singleton
    public static class RacyStore implements SocialPostStore {
        final InMemorySocialPostStore delegate = new InMemorySocialPostStore();
        final AtomicReference<UnaryOperator<StoredPost>> beforeReplace = new AtomicReference<>();
        final AtomicReference<UnaryOperator<StoredPost>> insteadOfInsert = new AtomicReference<>();

        @Override
        public Optional<StoredPost> find(String postId) {
            return delegate.find(postId);
        }

        @Override
        public boolean insert(StoredPost post) {
            UnaryOperator<StoredPost> other = insteadOfInsert.getAndSet(null);
            if (other != null) {
                delegate.insert(other.apply(post));
            }
            return delegate.insert(post);
        }

        @Override
        public boolean replace(StoredPost post, long expectedVersion) {
            UnaryOperator<StoredPost> other = beforeReplace.getAndSet(null);
            if (other != null) {
                StoredPost current = delegate.find(post.postId()).orElseThrow();
                assertTrue(delegate.replace(other.apply(current), current.version()));
            }
            return delegate.replace(post, expectedVersion);
        }

        @Override
        public Optional<String> findPostIdByRef(Network network, String ref) {
            return delegate.findPostIdByRef(network, ref);
        }

        @Override
        public List<StoredPost> recent(int limit) {
            return delegate.recent(limit);
        }
    }

    @Inject
    Social social;
    @Inject
    ReactiveSocial reactive;
    @Inject
    RacyStore store;
    @Inject
    TestAdapters.AdapterA a;
    @Inject
    TestAdapters.AdapterB b;

    @BeforeEach
    void reset() {
        a.reset();
        b.reset();
        store.beforeReplace.set(null);
        store.insteadOfInsert.set(null);
    }

    static Post note(String id) {
        return Post.builder(id).text("Hello").build();
    }

    static String newId() {
        return "post-" + UUID.randomUUID();
    }

    @Test
    void versionConflictsAreRetried() {
        String id = newId();
        a.failNext(FailureCode.UNAVAILABLE, true);
        store.beforeReplace.set(p -> p); // conflict on saving the first call's results
        PublishResult first = social.publish("alice", note(id));
        assertFailed(first, A, FailureCode.UNAVAILABLE);
        assertPublished(first, B);
        assertEquals(Status.FAILED, state(id, A).status());
        assertEquals(Status.PUBLISHED, state(id, B).status());

        store.beforeReplace.set(p -> p); // conflict on taking the repeat call's lease
        PublishResult second = social.publish("alice", note(id));
        assertTrue(second.allPublished(), second.toString());
        assertEquals(2, a.requests().size());
        assertEquals(1, b.requests().size());
        assertEquals(Status.PUBLISHED, state(id, A).status());
    }

    @Test
    void anotherRunsPublishedStateIsKept() {
        String id = newId();
        store.beforeReplace.set(p -> with(p, A, published(A, "other-run")));

        PublishResult r = social.publish("alice", note(id));

        NetworkResult.Published pa = assertPublished(r, A);
        assertFalse(pa.resent());
        assertEquals(Map.of("id", "other-run"), pa.refs());
        assertEquals("other-run", state(id, A).refs().get("id"));
        assertEquals(Status.PUBLISHED, state(id, B).status());
    }

    @Test
    void lostInsertIsARepeat() {
        String id = newId();
        store.insteadOfInsert.set(p -> withNetworks(p, Map.of(A, published(A, "other-run"))));

        PublishResult r = social.publish("alice", note(id));

        assertFalse(assertPublished(r, A).resent());
        assertTrue(a.requests().isEmpty());
        assertTrue(assertPublished(r, B).resent());
        assertEquals(1, b.requests().size());
    }

    @Test
    void liveLeaseBlocksASecondRun() {
        String id = newId();
        store.insteadOfInsert.set(p -> withNetworks(p, Map.of(A, lease(A, Instant.now()), B, lease(B, Instant.now()))));

        PublishResult r = social.publish("alice", note(id));

        for (Network n : List.of(A, B)) {
            NetworkResult.Failed f = assertFailed(r, n, FailureCode.IN_PROGRESS);
            assertTrue(f.retryable());
            assertEquals("another publish of this post is in progress", f.reason());
        }
        assertTrue(a.requests().isEmpty() && b.requests().isEmpty());
    }

    @Test
    void expiredLeaseIsRetried() {
        String id = newId();
        Instant longAgo = Instant.now().minus(Duration.ofHours(1));
        store.insteadOfInsert.set(p -> withNetworks(p, Map.of(A, lease(A, longAgo), B, published(B, "b-1"))));

        PublishResult r = social.publish("alice", note(id));

        assertTrue(assertPublished(r, A).resent());
        NetworkState previous = a.requests().get(0).request().previous().orElseThrow();
        assertEquals(Status.FAILED, previous.status());
        assertEquals("IN_PROGRESS", previous.code().orElseThrow());
        assertTrue(b.requests().isEmpty());
        assertEquals(Status.PUBLISHED, state(id, A).status());
    }

    @Test
    void cancelledPublishStillSaves() throws InterruptedException {
        String id = newId();
        CountDownLatch release = new CountDownLatch(1);
        a.holdUntil(release);
        b.holdUntil(release);
        try {
            Cancellable subscription = reactive.publish("alice", note(id)).subscribe().with(r -> {
            }, t -> {
            });
            awaitTrue(() -> a.requests().size() == 1 && b.requests().size() == 1, "adapters called");
            subscription.cancel();
        } finally {
            release.countDown();
        }
        awaitTrue(() -> social.status(id).map(p -> p.networks().values().stream()
                .allMatch(s -> s.status() == Status.PUBLISHED) && p.networks().size() == 2).orElse(false),
                "both networks saved as published");
    }

    private NetworkState state(String id, Network network) {
        return social.status(id).orElseThrow().networks().get(network);
    }

    private static void awaitTrue(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        fail("timed out waiting: " + what);
    }

    private static NetworkState published(Network network, String ref) {
        return new NetworkState(network, Status.PUBLISHED, Map.of("id", ref), Optional.empty(), Optional.empty(),
                Instant.now(), Optional.empty());
    }

    private static NetworkState lease(Network network, Instant at) {
        return new NetworkState(network, Status.FAILED, Map.of(), Optional.of("IN_PROGRESS"),
                Optional.of("another publish of this post is in progress"), at, Optional.empty());
    }

    private static StoredPost with(StoredPost post, Network network, NetworkState state) {
        Map<Network, NetworkState> states = new LinkedHashMap<>(post.networks());
        states.put(network, state);
        return withNetworks(post, states);
    }

    private static StoredPost withNetworks(StoredPost post, Map<Network, NetworkState> states) {
        return new StoredPost(post.postId(), post.account(), post.kind(), post.visibility(), post.snapshot(), states,
                post.version());
    }
}
