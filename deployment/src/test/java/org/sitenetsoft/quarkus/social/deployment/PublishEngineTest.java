package org.sitenetsoft.quarkus.social.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sitenetsoft.quarkus.social.deployment.TestAdapters.A;
import static org.sitenetsoft.quarkus.social.deployment.TestAdapters.B;
import static org.sitenetsoft.quarkus.social.test.SocialAssertions.assertFailed;
import static org.sitenetsoft.quarkus.social.test.SocialAssertions.assertPublished;
import static org.sitenetsoft.quarkus.social.test.SocialAssertions.assertSkipped;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.social.FailureCode;
import org.sitenetsoft.quarkus.social.Network;
import org.sitenetsoft.quarkus.social.NetworkResult;
import org.sitenetsoft.quarkus.social.Post;
import org.sitenetsoft.quarkus.social.PublishResult;
import org.sitenetsoft.quarkus.social.ReactiveSocial;
import org.sitenetsoft.quarkus.social.SkipReason;
import org.sitenetsoft.quarkus.social.Social;
import org.sitenetsoft.quarkus.social.SocialException;
import org.sitenetsoft.quarkus.social.engine.PublishEngine;
import org.sitenetsoft.quarkus.social.spi.AdapterRequest;
import org.sitenetsoft.quarkus.social.store.DeliveryProgress;
import org.sitenetsoft.quarkus.social.store.NetworkState;
import org.sitenetsoft.quarkus.social.store.StoredPost;
import org.sitenetsoft.quarkus.social.test.RecordingSocialAdapter.Operation;
import org.sitenetsoft.quarkus.social.test.RecordingSocialAdapter.RecordedRequest;

import io.quarkus.test.QuarkusExtensionTest;
import io.vertx.core.Vertx;

public class PublishEngineTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(TestAdapters.class, TestAdapters.AdapterA.class,
                    TestAdapters.AdapterB.class, TestAdapters.ProgressEvents.class))
            .overrideConfigKey("quarkus.social.timeout", "200ms");

    @Inject
    Social social;
    @Inject
    ReactiveSocial reactive;
    @Inject
    PublishEngine engine;
    @Inject
    TestAdapters.AdapterA a;
    @Inject
    TestAdapters.AdapterB b;
    @Inject
    TestAdapters.ProgressEvents events;
    @Inject
    Vertx vertx;

    @BeforeEach
    void reset() {
        a.reset();
        b.reset();
        events.received.clear();
    }

    static String newId() {
        return "post-" + UUID.randomUUID();
    }

    static Post note(String id) {
        return Post.builder(id).text("Hello").build();
    }

    @Test
    void publishCallsEveryAdapter() {
        String id = newId();
        PublishResult r = social.publish("alice", note(id));

        assertEquals(id, r.postId());
        for (var adapter : List.of(a, b)) {
            List<RecordedRequest> requests = adapter.requests();
            assertEquals(1, requests.size(), adapter.network() + ": " + requests);
            RecordedRequest req = requests.get(0);
            assertEquals(Operation.PUBLISH, req.operation());
            assertEquals("alice", req.request().account());
            assertEquals("alice", req.request().identity());
            assertEquals(id, req.request().post().id());
            assertEquals("Hello", req.request().content().text());
            assertEquals("<p>Hello</p>", req.request().content().html());
            assertTrue(req.request().previous().isEmpty());
        }
        assertTrue(r.allPublished(), r.toString());
    }

    @Test
    void perNetworkResults() {
        String id = newId();
        b.skipNext(SkipReason.VISIBILITY_NOT_SUPPORTED);
        PublishResult r = social.publish("alice", note(id));

        assertEquals(Set.of(A, B), r.results().keySet());
        NetworkResult.Published pa = assertPublished(r, A);
        assertTrue(pa.resent());
        assertFalse(pa.refs().isEmpty());
        assertSkipped(r, B, SkipReason.VISIBILITY_NOT_SUPPORTED);

        StoredPost stored = social.status(id).orElseThrow();
        assertEquals("alice", stored.account());
        assertEquals(id, stored.snapshot().id());
        assertEquals(NetworkState.Status.PUBLISHED, stored.networks().get(A).status());
        assertEquals(pa.refs(), withoutUrl(stored.networks().get(A).refs()));
        NetworkState sb = stored.networks().get(B);
        assertEquals(NetworkState.Status.SKIPPED, sb.status());
        assertEquals("VISIBILITY_NOT_SUPPORTED", sb.code().orElseThrow());
    }

    @Test
    void narrowedByPostNetworks() {
        String id = newId();
        PublishResult r = social.publish("alice", Post.builder(id).text("Hello").networks(Set.of(B)).build());

        assertEquals(Set.of(B), r.results().keySet());
        assertPublished(r, B);
        assertTrue(a.requests().isEmpty());
    }

    @Test
    void postNetworksWithoutAdapterThrow() {
        Post post = Post.builder(newId()).text("Hello").networks(Set.of(new Network("nowhere"))).build();
        SocialException e = assertThrows(SocialException.class, () -> social.publish("alice", post));
        assertTrue(e.getMessage().contains("nowhere"), e.getMessage());
        assertTrue(a.requests().isEmpty() && b.requests().isEmpty());
    }

    @Test
    void notLinkedIsSkippedWithoutCall() {
        a.unlink("bob");
        PublishResult r = social.publish("bob", note(newId()));

        NetworkResult.Skipped s = assertSkipped(r, A, SkipReason.NOT_LINKED);
        assertEquals("account bob is not linked to test-a", s.detail());
        assertTrue(a.requests().isEmpty());
        assertPublished(r, B);
    }

    @Test
    void unavailableAdapterIsSkippedWithoutCall() {
        a.unavailable("login mode has no app account");
        PublishResult r = social.publish("alice", note(newId()));

        NetworkResult.Skipped s = assertSkipped(r, A, SkipReason.NOT_LINKED);
        assertEquals("login mode has no app account", s.detail());
        assertTrue(a.requests().isEmpty());
        assertPublished(r, B);
    }

    @Test
    void failedLinkLookupIsRejected() {
        a.failLinkNext(new IllegalStateException("account alice has several linked DIDs"));
        PublishResult r = social.publish("alice", note(newId()));

        NetworkResult.Failed f = assertFailed(r, A, FailureCode.REJECTED);
        assertFalse(f.retryable());
        assertEquals("account alice has several linked DIDs", f.reason());
        assertTrue(a.requests().isEmpty());
        assertPublished(r, B);
    }

    @Test
    void hangingAdapterTimesOutOthersPublished() {
        a.hangNext();
        long start = System.nanoTime();
        PublishResult r = social.publish("alice", note(newId()));
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        NetworkResult.Failed f = assertFailed(r, A, FailureCode.TIMEOUT);
        assertTrue(f.retryable());
        assertPublished(r, B);
        assertTrue(took.compareTo(Duration.ofSeconds(10)) < 0, took.toString());
    }

    @Test
    void throwingAdapterIsFailedNotThrown() {
        a.throwNext(new IllegalStateException("boom"));
        PublishResult r = social.publish("alice", note(newId()));

        NetworkResult.Failed f = assertFailed(r, A, FailureCode.INTERNAL);
        assertTrue(f.retryable());
        assertTrue(f.reason().contains("IllegalStateException") && f.reason().contains("boom"), f.reason());
        assertFalse(f.reason().contains("\n") || f.reason().contains("\tat "), f.reason());
        assertPublished(r, B);
    }

    @Test
    void retryOnlyRetriesUnpublished() {
        String id = newId();
        a.failNext(FailureCode.UNAVAILABLE, true);
        PublishResult first = social.publish("alice", note(id));
        assertFailed(first, A, FailureCode.UNAVAILABLE);
        NetworkResult.Published firstB = assertPublished(first, B);

        PublishResult second = social.publish("alice", note(id));

        assertEquals(2, a.requests().size());
        RecordedRequest retry = a.requests().get(1);
        assertEquals(Operation.PUBLISH, retry.operation());
        assertEquals(NetworkState.Status.FAILED, retry.request().previous().orElseThrow().status());
        assertTrue(assertPublished(second, A).resent());

        assertEquals(1, b.requests().size());
        NetworkResult.Published againB = assertPublished(second, B);
        assertFalse(againB.resent());
        assertEquals(firstB.refs(), againB.refs());
        assertEquals(firstB.url(), againB.url());
        assertEquals(firstB.note(), againB.note());
    }

    @Test
    void repeatPublishRetriesTheStoredPost() {
        String id = newId();
        java.time.Instant first = java.time.Instant.parse("2026-11-01T08:00:00Z");
        a.failNext(FailureCode.UNAVAILABLE, true);
        social.publish("alice", Post.builder(id).text("Original").publishedAt(first).build());

        // the caller builds the post again: a new publication time (Post.Builder defaults to now) and new text
        social.publish("alice", Post.builder(id).text("Changed").build());

        assertEquals(2, a.requests().size());
        AdapterRequest retry = a.requests().get(1).request();
        assertEquals(first, retry.post().publishedAt());
        assertEquals(java.util.Optional.of("Original"), retry.post().text());
        assertEquals("Original", retry.content().text());
    }

    @Test
    void updateSeesTheStoredPost() {
        String id = newId();
        social.publish("alice", Post.builder(id).text("Original").build());
        social.update("alice", Post.builder(id).text("Edited").build());
        AdapterRequest update = a.requests().get(1).request();
        assertEquals(Operation.UPDATE, a.requests().get(1).operation());
        assertEquals(java.util.Optional.of("Edited"), update.post().text());
        assertEquals(java.util.Optional.of("Original"), update.stored().orElseThrow().text());
        assertTrue(a.requests().get(0).request().stored().isEmpty());
    }

    @Test
    void fullyPublishedRepeatThrows() {
        String id = newId();
        social.publish("alice", note(id));

        SocialException e = assertThrows(SocialException.class, () -> social.publish("alice", note(id)));
        assertEquals("post " + id + " is already published; use update", e.getMessage());
        assertEquals(1, a.requests().size());
        assertEquals(1, b.requests().size());
    }

    @Test
    void repeatByAnotherAccountThrows() {
        String id = newId();
        a.failNext(FailureCode.UNAVAILABLE, true);
        social.publish("alice", note(id));

        assertThrows(SocialException.class, () -> social.publish("mallory", note(id)));
        assertEquals(1, a.requests().size());
    }

    @Test
    void repeatAfterDeleteThrows() {
        String id = newId();
        social.publish("alice", note(id));
        social.delete("alice", id);

        SocialException e = assertThrows(SocialException.class, () -> social.publish("alice", note(id)));
        assertEquals("post " + id + " was deleted; publish it under a new id", e.getMessage());
        assertEquals(2, a.requests().size());
    }

    @Test
    void reservedRefNameIsAnAdapterBug() {
        a.failNext(FailureCode.UNAVAILABLE, true, Map.of(NetworkState.URL_REF, "https://recording.example/x"));
        PublishResult r = social.publish("alice", note(newId()));

        NetworkResult.Failed f = assertFailed(r, A, FailureCode.INTERNAL);
        assertTrue(f.reason().contains("reserved"), f.reason());
    }

    @Test
    void blockingAndReactiveAgree() {
        String blockingId = newId();
        b.failNext(FailureCode.REJECTED, false);
        PublishResult viaBlocking = social.publish("alice", note(blockingId));
        StoredPost blockingState = social.status(blockingId).orElseThrow();

        String reactiveId = newId();
        b.failNext(FailureCode.REJECTED, false);
        PublishResult viaReactive = reactive.publish("alice", note(reactiveId)).await().atMost(Duration.ofSeconds(10));
        StoredPost reactiveState = reactive.status(reactiveId).await().atMost(Duration.ofSeconds(10)).orElseThrow();

        assertEquals(shape(viaBlocking), shape(viaReactive));
        assertEquals(states(blockingState), states(reactiveState));
        assertEquals(social.capabilities(), reactive.capabilities().await().atMost(Duration.ofSeconds(10)));
        assertEquals(Set.of(A, B), social.capabilities().keySet());
    }

    @Test
    void blockingSocialRefusesTheEventLoop() throws Exception {
        CompletableFuture<Throwable> outcome = new CompletableFuture<>();
        vertx.getOrCreateContext().runOnContext(v -> {
            try {
                social.publish("alice", note(newId()));
                outcome.complete(null);
            } catch (Throwable t) {
                outcome.complete(t);
            }
        });
        Throwable t = outcome.get(10, TimeUnit.SECONDS);
        IllegalStateException e = assertInstanceOf(IllegalStateException.class, t);
        assertTrue(e.getMessage().contains("event loop") && e.getMessage().contains("ReactiveSocial"), e.getMessage());
        assertTrue(a.requests().isEmpty());
    }

    @Test
    void deliveryProgressUpdatesStoreAndFiresEvent() {
        String id = newId();
        social.publish("alice", note(id));

        engine.recordProgress(id, A, new DeliveryProgress(1, 0, 2)).await().atMost(Duration.ofSeconds(10));

        NetworkState state = social.status(id).orElseThrow().networks().get(A);
        assertEquals(NetworkState.Status.PUBLISHED, state.status());
        assertEquals(new DeliveryProgress(1, 0, 2), state.delivery().orElseThrow());
        assertEquals(1, events.received.size());
        assertEquals(id, events.received.get(0).postId());
        assertEquals(A, events.received.get(0).network());
        assertEquals(new DeliveryProgress(1, 0, 2), events.received.get(0).progress());
    }

    /**
     * The neutral core never depends on a protocol: nothing of ActivityPub, AT Protocol or ActivityStreams is on the
     * class path here (runtime + deployment + test module), and the runtime POM declares no such dependency.
     */
    @Test
    void coreHasNoProtocolDependency() throws IOException, URISyntaxException {
        List<String> forbidden = List.of("org/sitenetsoft/quarkus/activitypub/", "org/sitenetsoft/quarkus/atproto/",
                "org/sitenetsoft/activitystreams/");
        ClassLoader[] loaders = { getClass().getClassLoader(), ClassLoader.getSystemClassLoader() };
        for (ClassLoader loader : loaders) {
            // sanity: the lookup finds the social package itself, so an empty answer below means "absent"
            assertTrue(loader.getResources("org/sitenetsoft/quarkus/social/").hasMoreElements());
            for (String pkg : forbidden) {
                List<URL> found = Collections.list(loader.getResources(pkg));
                assertTrue(found.isEmpty(), pkg + " is on the class path: " + found);
            }
        }
        String pom = runtimePom();
        assertTrue(pom.contains("<artifactId>quarkus-social</artifactId>"), "not the runtime POM");
        // the adapters are listed as conditional dependencies of the extension descriptor, not as Maven dependencies
        pom = pom.replaceAll("(?s)<conditionalDependencies>.*?</conditionalDependencies>", "");
        for (String word : List.of("activitypub", "atproto", "activitystreams")) {
            assertFalse(pom.contains(word), "runtime POM mentions " + word);
        }
    }

    /** runtime/pom.xml next to the compiled classes, or the copy Maven puts in the jar. */
    private static String runtimePom() throws IOException, URISyntaxException {
        Path location = Path.of(Social.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (Files.isDirectory(location)) {
            return Files.readString(location.resolve("../../pom.xml").normalize());
        }
        try (InputStream in = Social.class.getResourceAsStream(
                "/META-INF/maven/org.sitenetsoft.quarkus/quarkus-social/pom.xml")) {
            assertTrue(in != null, "no POM in " + location);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> withoutUrl(Map<String, String> refs) {
        Map<String, String> copy = new LinkedHashMap<>(refs);
        copy.remove(NetworkState.URL_REF);
        return copy;
    }

    private static Map<Network, String> shape(PublishResult r) {
        Map<Network, String> shape = new LinkedHashMap<>();
        r.results().forEach((n, res) -> shape.put(n, switch (res) {
            case NetworkResult.Published p -> "Published resent=" + p.resent();
            case NetworkResult.Skipped s -> "Skipped " + s.reason();
            case NetworkResult.Failed f -> "Failed " + f.code() + " retryable=" + f.retryable();
        }));
        return shape;
    }

    private static Map<Network, String> states(StoredPost post) {
        Map<Network, String> states = new LinkedHashMap<>();
        post.networks().forEach((n, s) -> states.put(n, s.status() + " " + s.code().orElse("")));
        return states;
    }
}
