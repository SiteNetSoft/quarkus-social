package org.sitenetsoft.quarkus.social.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.sitenetsoft.quarkus.social.Kind;
import org.sitenetsoft.quarkus.social.Network;
import org.sitenetsoft.quarkus.social.Post;
import org.sitenetsoft.quarkus.social.Visibility;
import org.sitenetsoft.quarkus.social.store.DeliveryProgress;
import org.sitenetsoft.quarkus.social.store.NetworkState;
import org.sitenetsoft.quarkus.social.store.NetworkState.Status;
import org.sitenetsoft.quarkus.social.store.SocialPostStore;
import org.sitenetsoft.quarkus.social.store.StoredPost;

/**
 * The behaviour every {@link SocialPostStore} must have. Extend it and return the store under test from
 * {@link #store()}; the store may be shared between tests, so each test uses fresh post ids.
 */
public abstract class SocialPostStoreContractTest {

    protected abstract SocialPostStore store();

    private static String id() {
        return "post-" + UUID.randomUUID();
    }

    private static Post post(String id) {
        return Post.builder(id).text("hello").build();
    }

    private static StoredPost stored(String id, Map<Network, NetworkState> networks) {
        return new StoredPost(id, "alice", Kind.NOTE, Visibility.PUBLIC, post(id), networks, 0);
    }

    private static NetworkState published(Network network, Map<String, String> refs) {
        return new NetworkState(network, Status.PUBLISHED, refs, Optional.empty(), Optional.empty(),
                Instant.parse("2026-01-02T03:04:05.123456Z"), Optional.empty());
    }

    @Test
    public void insertTwiceFalse() {
        String id = id();
        assertTrue(store().insert(stored(id, Map.of())));
        assertFalse(store().insert(stored(id, Map.of())));
        assertEquals(0, store().find(id).orElseThrow().version());
        assertTrue(store().find(id()).isEmpty());
    }

    @Test
    public void replaceWithStaleVersionFalse() {
        String id = id();
        store().insert(stored(id, Map.of()));
        assertTrue(store().replace(stored(id, Map.of()), 0));
        assertEquals(1, store().find(id).orElseThrow().version());
        assertFalse(store().replace(stored(id, Map.of()), 0));
        assertEquals(1, store().find(id).orElseThrow().version());
        // the stored version is expectedVersion + 1 whatever the argument says
        assertTrue(store().replace(stored(id, Map.of()).withVersion(42), 1));
        assertEquals(2, store().find(id).orElseThrow().version());
        // unknown post
        assertFalse(store().replace(stored(id(), Map.of()), 0));
    }

    @Test
    public void findByRefAfterReplaceAddsRef() {
        String id = id();
        String ref = "at://did:plc:example/app.bsky.feed.post/" + id;
        String ref2 = "https://ap-test.example.net/objects/" + id;
        store().insert(stored(id, Map.of()));
        assertTrue(store().findPostIdByRef(Network.ATPROTO, ref).isEmpty());

        assertTrue(store().replace(stored(id, Map.of(Network.ATPROTO, published(Network.ATPROTO, Map.of("post.uri", ref)),
                Network.ACTIVITYPUB, published(Network.ACTIVITYPUB, Map.of("object", ref2)))), 0));
        assertEquals(Optional.of(id), store().findPostIdByRef(Network.ATPROTO, ref));
        assertEquals(Optional.of(id), store().findPostIdByRef(Network.ACTIVITYPUB, ref2));
        // a ref is per network
        assertTrue(store().findPostIdByRef(Network.ACTIVITYPUB, ref).isEmpty());

        // refs change (a recreated post): the old one stops resolving, every ref value is searched
        String recreated = ref + "-2";
        assertTrue(store().replace(stored(id, Map.of(Network.ATPROTO, published(Network.ATPROTO,
                Map.of("post.uri", recreated, "post.cid", "cid-" + id, NetworkState.URL_REF, "https://blog.example/" + id)))), 1));
        assertTrue(store().findPostIdByRef(Network.ATPROTO, ref).isEmpty());
        assertTrue(store().findPostIdByRef(Network.ACTIVITYPUB, ref2).isEmpty());
        assertEquals(Optional.of(id), store().findPostIdByRef(Network.ATPROTO, recreated));
        assertEquals(Optional.of(id), store().findPostIdByRef(Network.ATPROTO, "cid-" + id));
        assertEquals(Optional.of(id), store().findPostIdByRef(Network.ATPROTO, "https://blog.example/" + id));
    }

    @Test
    public void recentOrderedNewestFirst() throws InterruptedException {
        String a = id();
        String b = id();
        String c = id();
        store().insert(stored(a, Map.of()));
        Thread.sleep(20);
        store().insert(stored(b, Map.of()));
        Thread.sleep(20);
        store().insert(stored(c, Map.of()));
        Thread.sleep(20);
        // writing a post again makes it the most recent
        assertTrue(store().replace(stored(a, Map.of()), 0));

        List<String> ids = store().recent(3).stream().map(StoredPost::postId).toList();
        assertEquals(List.of(a, c, b), ids);
        assertEquals(1, store().recent(1).size());
        assertTrue(store().recent(0).isEmpty());
    }

    @Test
    public void snapshotRoundTrips() {
        String id = id();
        Instant publishedAt = Instant.parse("2026-03-04T05:06:07.123456789Z");
        Post full = Post.builder(id).kind(Kind.ARTICLE).title("Café 👩‍💻 日本語").summary("Résumé — “quoted” \"x\"")
                .text("Text with emoji 👩‍💻, accents é and a URL https://blog.example/é #quarkus\nline two")
                .html("<p>Café <a href=\"https://blog.example/é\">link</a> 👩‍💻</p>")
                .url(URI.create("https://blog.example/posts/%C3%A9")).language("fr").tags(List.of("quarkus", "été"))
                .visibility(Visibility.UNLISTED).sensitive(true).contentWarning("CW: ☂️")
                .publishedAt(publishedAt).networks(Set.of(Network.ACTIVITYPUB, Network.ATPROTO))
                .atprotoLabels(List.of("graphic-media", "porn")).build();
        Post minimal = Post.builder(id()).text("x").publishedAt(Instant.EPOCH).build();

        NetworkState failed = new NetworkState(Network.ATPROTO, Status.FAILED, Map.of(), Optional.of("IN_PROGRESS"),
                Optional.of("détail 👩‍💻"), Instant.parse("2026-01-02T03:04:05.5Z"), Optional.empty());
        NetworkState done = new NetworkState(Network.ACTIVITYPUB, Status.PUBLISHED,
                Map.of("object", "https://ap-test.example.net/o/é", NetworkState.URL_REF, "https://blog.example/é"),
                Optional.empty(), Optional.of("note"), Instant.parse("2026-01-02T03:04:06Z"),
                Optional.of(new DeliveryProgress(3, 1, 2)));
        Network custom = new Network("custom-net");
        NetworkState skipped = new NetworkState(custom, Status.SKIPPED, Map.of(), Optional.of("NOT_LINKED"),
                Optional.of("account alice is not linked"), Instant.parse("2026-01-02T03:04:07Z"), Optional.empty());

        StoredPost original = new StoredPost(id, "alice", Kind.ARTICLE, Visibility.UNLISTED, full,
                Map.of(Network.ATPROTO, failed, Network.ACTIVITYPUB, done, custom, skipped), 0);
        assertTrue(store().insert(original));

        StoredPost read = store().find(id).orElseThrow();
        assertEquals("alice", read.account());
        assertEquals(Kind.ARTICLE, read.kind());
        assertEquals(Visibility.UNLISTED, read.visibility());
        assertEquals(0, read.version());
        assertEquals(original.networks(), read.networks());
        assertPostEquals(full, read.snapshot());

        // the minimal post: every Optional empty, defaults
        String minimalId = minimal.id();
        assertTrue(store().insert(new StoredPost(minimalId, "bob", Kind.NOTE, Visibility.PUBLIC, minimal, Map.of(), 0)));
        Post readMinimal = store().find(minimalId).orElseThrow().snapshot();
        assertPostEquals(minimal, readMinimal);
        assertNotNull(readMinimal.publishedAt());

        // and after a replace
        assertTrue(store().replace(original.withVersion(0), 0));
        StoredPost replaced = store().find(id).orElseThrow();
        assertEquals(1, replaced.version());
        assertEquals(original.networks(), replaced.networks());
        assertPostEquals(full, replaced.snapshot());
    }

    private static void assertPostEquals(Post expected, Post actual) {
        assertEquals(expected.id(), actual.id());
        assertEquals(expected.kind(), actual.kind());
        assertEquals(expected.title(), actual.title());
        assertEquals(expected.summary(), actual.summary());
        assertEquals(expected.text(), actual.text());
        assertEquals(expected.html(), actual.html());
        assertEquals(expected.url(), actual.url());
        assertEquals(expected.language(), actual.language());
        assertEquals(expected.tags(), actual.tags());
        assertEquals(expected.visibility(), actual.visibility());
        assertEquals(expected.sensitive(), actual.sensitive());
        assertEquals(expected.contentWarning(), actual.contentWarning());
        assertEquals(expected.publishedAt(), actual.publishedAt());
        assertEquals(expected.networks(), actual.networks());
        assertEquals(expected.atprotoLabels(), actual.atprotoLabels());
    }

    @Test
    public void concurrentReplaceExactlyOneWins() throws Exception {
        int contenders = 2;
        for (int round = 0; round < 5; round++) {
            String id = id();
            store().insert(stored(id, Map.of()));
            ExecutorService pool = Executors.newFixedThreadPool(contenders);
            try {
                CountDownLatch ready = new CountDownLatch(contenders);
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Boolean>> results = new java.util.ArrayList<>();
                for (int i = 0; i < contenders; i++) {
                    String ref = "ref-" + id + "-" + i;
                    results.add(pool.submit(() -> {
                        ready.countDown();
                        go.await();
                        return store().replace(stored(id, Map.of(Network.ATPROTO,
                                published(Network.ATPROTO, Map.of("post.uri", ref)))), 0);
                    }));
                }
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                go.countDown();
                int wins = 0;
                for (Future<Boolean> result : results) {
                    if (result.get(30, TimeUnit.SECONDS)) {
                        wins++;
                    }
                }
                assertEquals(1, wins, "exactly one replace with the same expected version wins");
                StoredPost read = store().find(id).orElseThrow();
                assertEquals(1, read.version());
                // the winner's ref resolves, the loser's does not
                long resolving = java.util.stream.IntStream.range(0, contenders)
                        .filter(i -> store().findPostIdByRef(Network.ATPROTO, "ref-" + id + "-" + i).isPresent()).count();
                assertEquals(1, resolving);
                assertEquals(1, read.networks().get(Network.ATPROTO).refs().size());
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    public void concurrentInsertExactlyOneWins() throws Exception {
        String id = id();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            Future<Boolean> first = pool.submit(() -> { go.await(); return store().insert(stored(id, Map.of())); });
            Future<Boolean> second = pool.submit(() -> { go.await(); return store().insert(stored(id, Map.of())); });
            go.countDown();
            assertEquals(1, (first.get(30, TimeUnit.SECONDS) ? 1 : 0) + (second.get(30, TimeUnit.SECONDS) ? 1 : 0));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, store().find(id).orElseThrow().version());
    }
}
