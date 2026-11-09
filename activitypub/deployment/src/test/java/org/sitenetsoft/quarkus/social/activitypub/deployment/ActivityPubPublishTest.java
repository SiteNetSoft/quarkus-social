package org.sitenetsoft.quarkus.social.activitypub.deployment;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sitenetsoft.quarkus.social.Network.ACTIVITYPUB;
import static org.sitenetsoft.quarkus.social.test.SocialAssertions.assertFailed;
import static org.sitenetsoft.quarkus.social.test.SocialAssertions.assertPublished;
import static org.sitenetsoft.quarkus.social.test.SocialAssertions.assertSkipped;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.activitystreams.ASJson;
import org.sitenetsoft.quarkus.activitypub.ActivityPubUrls;
import org.sitenetsoft.quarkus.activitypub.actor.LocalActors;
import org.sitenetsoft.quarkus.activitypub.outbound.DeliveryEvents.DeliveriesSubmitted;
import org.sitenetsoft.quarkus.activitypub.store.FollowState;
import org.sitenetsoft.quarkus.activitypub.store.Stores;
import org.sitenetsoft.quarkus.activitypub.test.FakeActor;
import org.sitenetsoft.quarkus.activitypub.test.FakeFediverseServer;
import org.sitenetsoft.quarkus.activitypub.test.ReceivedRequest;
import org.sitenetsoft.quarkus.social.FailureCode;
import org.sitenetsoft.quarkus.social.NetworkResult;
import org.sitenetsoft.quarkus.social.Post;
import org.sitenetsoft.quarkus.social.SkipReason;
import org.sitenetsoft.quarkus.social.Social;
import org.sitenetsoft.quarkus.social.Visibility;
import org.sitenetsoft.quarkus.social.activitypub.ActivityPubSocialAdapter;
import org.sitenetsoft.quarkus.social.activitypub.PostMapper;
import org.sitenetsoft.quarkus.social.content.PostContent;
import org.sitenetsoft.quarkus.social.events.SocialDeliveryProgress;
import org.sitenetsoft.quarkus.social.spi.AdapterRequest;
import org.sitenetsoft.quarkus.social.store.DeliveryProgress;
import org.sitenetsoft.quarkus.social.store.NetworkState;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * Publishing through the adapter to a fake Mastodon whose actor {@code bob} follows the local actor {@code alice}:
 * the app serves on a port reserved up front (the fake fetches alice's key to verify our signatures).
 */
public class ActivityPubPublishTest {

    static final int PORT = reservedPort();
    static final String BASE = "http://127.0.0.1:" + PORT;
    static final Duration WAIT = Duration.ofSeconds(20);

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Alice.class, Observed.class))
            .overrideConfigKey("quarkus.http.test-port", String.valueOf(PORT))
            .overrideConfigKey("quarkus.activitypub.base-url", BASE)
            .overrideConfigKey("quarkus.activitypub.delivery.poll-interval", "1s")
            .overrideConfigKey("quarkus.social.timeout", "3s")
            .overrideConfigKey("quarkus.safe-fetch.https-only", "false")
            .overrideConfigKey("quarkus.safe-fetch.allow-private-addresses", "true");

    static final FakeFediverseServer fake = new FakeFediverseServer();

    @Inject
    Social social;
    @Inject
    ActivityPubSocialAdapter adapter;
    @Inject
    Stores stores;
    @Inject
    Observed observed;
    @Inject
    ActivityPubUrls urls;

    @BeforeAll
    static void startFake() {
        fake.start();
    }

    @AfterAll
    static void stopFake() {
        fake.stop();
    }

    @BeforeEach
    void bobFollowsAlice() {
        FakeActor bob = fake.actor("bob");
        stores.followers().add(URI.create(BASE + "/users/alice"), bob.id(), bob.inbox(), FollowState.ACCEPTED,
                URI.create(bob.id() + "/follows/1"));
    }

    @Test
    void publishDeliversCreate() {
        Post post = Post.builder(newId()).text("Hello fediverse").build();
        NetworkResult.Published published = assertPublished(social.publish("alice", post), ACTIVITYPUB);
        URI objectId = URI.create(published.refs().get("object"));
        assertTrue(published.refs().containsKey("activity"), published.refs().toString());
        assertEquals(Optional.of(objectId), published.url());
        assertEquals(Optional.of("queued for 1 inbox, 0 failed at once"), published.note());

        ReceivedRequest request = awaitInbox("Create", objectId);
        assertTrue(request.verified(), "the delivery is signed with alice's key");
        JsonNode create = json(request);
        assertEquals(published.refs().get("activity"), create.path("id").asText());
        assertEquals("Note", create.path("object").path("type").asText());
        assertEquals("<p>Hello fediverse</p>", create.path("object").path("content").asText());
    }

    @Test
    void deliveryProgressReachesStore() {
        Post post = Post.builder(newId()).text("Counting deliveries").build();
        assertPublished(social.publish("alice", post), ACTIVITYPUB);
        await().atMost(WAIT).until(() -> delivery(post.id()).equals(Optional.of(new DeliveryProgress(1, 0, 0))));
        await().atMost(WAIT).until(() -> observed.progress(post.id()).size() == 1);
        assertEquals(List.of(new DeliveryProgress(1, 0, 0)), observed.progress(post.id()));
    }

    /** carol has no followers: no delivery event will ever come, so the counts are written once published. */
    @Test
    void nothingToDeliverIsFinishedAtOnce() {
        Post post = Post.builder(newId()).text("Nobody gets this").build();
        NetworkResult.Published published = assertPublished(social.publish("carol", post), ACTIVITYPUB);
        assertEquals(Optional.of("queued for 0 inboxes, 0 failed at once"), published.note());
        await().atMost(WAIT).until(() -> delivery(post.id()).equals(Optional.of(new DeliveryProgress(0, 0, 0))));
        await().atMost(WAIT).until(() -> observed.progress(post.id()).size() == 1);
        assertEquals(List.of(new DeliveryProgress(0, 0, 0)), observed.progress(post.id()));
    }

    @Test
    void updateSendsUpdate() {
        Post post = Post.builder(newId()).text("First version").build();
        URI objectId = objectId(assertPublished(social.publish("alice", post), ACTIVITYPUB));
        Post edited = Post.builder(post.id()).text("Second version").build();
        NetworkResult.Published updated = assertPublished(social.update("alice", edited), ACTIVITYPUB);
        assertTrue(updated.refs().containsKey("activity.update.1"), updated.refs().toString());
        assertEquals(Optional.of(objectId), updated.url());

        JsonNode create = json(awaitInbox("Create", objectId));
        JsonNode update = json(awaitInbox("Update", objectId));
        assertEquals(updated.refs().get("activity.update.1"), update.path("id").asText());
        assertEquals("<p>Second version</p>", update.path("object").path("content").asText());
        assertEquals(create.path("object").path("published").asText(),
                update.path("object").path("published").asText(), "an edit keeps the publication time");
        assertTrue(update.path("object").hasNonNull("updated"));

        NetworkResult.Published again = assertPublished(social.update("alice",
                Post.builder(post.id()).text("Third version").build()), ACTIVITYPUB);
        assertTrue(again.refs().containsKey("activity.update.2"), again.refs().toString());
    }

    /** Neither the Update nor its object addresses Public in any audience field. */
    @Test
    void followersOnlyUpdateStaysFollowersOnly() {
        Post post = Post.builder(newId()).text("For followers").visibility(Visibility.FOLLOWERS).build();
        URI objectId = objectId(assertPublished(social.publish("alice", post), ACTIVITYPUB));
        assertPublished(social.update("alice", Post.builder(post.id()).text("Still for followers")
                .visibility(Visibility.FOLLOWERS).build()), ACTIVITYPUB);

        JsonNode update = json(awaitInbox("Update", objectId));
        for (JsonNode node : List.of(update, update.path("object"))) {
            for (String field : List.of("to", "cc", "bto", "bcc", "audience")) {
                assertFalse(node.path(field).toString().contains("Public"), field + ": " + node.path(field));
            }
            assertTrue(node.path("to").toString().contains(BASE + "/users/alice/followers"), node.toString());
        }
    }

    @Test
    void deleteSendsDelete() {
        Post post = Post.builder(newId()).text("Soon gone").build();
        URI objectId = objectId(assertPublished(social.publish("alice", post), ACTIVITYPUB));
        NetworkResult.Published deleted = assertPublished(social.delete("alice", post.id()), ACTIVITYPUB);
        assertTrue(deleted.refs().containsKey("activity.delete"), deleted.refs().toString());

        JsonNode delete = json(awaitInbox("Delete", objectId));
        assertEquals(deleted.refs().get("activity.delete"), delete.path("id").asText());
        assertEquals(NetworkState.Status.DELETED, social.status(post.id()).orElseThrow().networks().get(ACTIVITYPUB)
                .status());
    }

    @Test
    void unknownAccountIsNotLinked() {
        Post post = Post.builder(newId()).text("Who am I?").build();
        assertSkipped(social.publish("nobody", post), ACTIVITYPUB, SkipReason.NOT_LINKED);
    }

    /**
     * A first call stored and queued the post but its answer was lost (the engine timed out): the retry reports the
     * stored object as published and sends no second {@code Create}.
     */
    @Test
    void retryAfterTimeoutDoesNotCreateAgain() {
        Post post = Post.builder(newId()).text("Sent once").build();
        AdapterRequest first = new AdapterRequest("alice", "alice", post, PostContent.of(post), Optional.empty());
        NetworkResult.Published one = (NetworkResult.Published) adapter.publish(first).await().atMost(WAIT);

        NetworkState timedOut = new NetworkState(ACTIVITYPUB, NetworkState.Status.FAILED,
                Map.of(NetworkState.URL_REF, "https://ignored.example/x"), Optional.of("TIMEOUT"),
                Optional.of("no answer"), Instant.now(), Optional.empty());
        AdapterRequest retry = new AdapterRequest("alice", "alice", post, PostContent.of(post),
                Optional.of(timedOut));
        NetworkResult.Published two = (NetworkResult.Published) adapter.publish(retry).await().atMost(WAIT);

        assertEquals(one.refs(), two.refs());
        assertEquals(one.url(), two.url());
        URI activityId = URI.create(one.refs().get("activity"));
        assertEquals(1, observed.submitted().stream().filter(activityId::equals).count(),
                "the Create was published once");
    }

    /**
     * The engine times out a publish whose ActivityPub call is still running; a retry meanwhile is answered
     * IN_PROGRESS without a second Create, and once the first call is done a retry reports its object, not re-sent.
     */
    @Test
    void retryWhileFirstPublishRunsIsInProgress() {
        Post post = Post.builder(newId()).text("Slow first call").build();
        URI objectId = PostMapper.objectId(urls, post.id());
        URI createId = PostMapper.createId(urls, post.id());
        CountDownLatch gate = observed.block(createId);
        try {
            assertFailed(social.publish("alice", post), ACTIVITYPUB, FailureCode.TIMEOUT);
            assertFailed(social.publish("alice", post), ACTIVITYPUB, FailureCode.IN_PROGRESS);
        } finally {
            gate.countDown();
        }
        awaitInbox("Create", objectId);

        // through the adapter: the engine keeps the IN_PROGRESS answer as a lease until it expires
        NetworkState inProgress = new NetworkState(ACTIVITYPUB, NetworkState.Status.FAILED, Map.of(),
                Optional.of("IN_PROGRESS"), Optional.of("still running"), Instant.now(), Optional.empty());
        AdapterRequest retry = new AdapterRequest("alice", "alice", post, PostContent.of(post),
                Optional.of(inProgress));
        await().atMost(WAIT).until(() -> adapter.publish(retry).await().atMost(WAIT)
                instanceof NetworkResult.Published p && !p.resent()
                && p.refs().equals(Map.of("object", objectId.toString(), "activity", createId.toString())));
        assertEquals(1, observed.submitted().stream().filter(createId::equals).count(), "one Create published");
        assertEquals(1, inbox("Create", objectId).size(), "one Create delivered");
    }

    // ---- helpers ----

    static String newId() {
        return "post-" + UUID.randomUUID();
    }

    static URI objectId(NetworkResult.Published published) {
        return URI.create(published.refs().get("object"));
    }

    Optional<DeliveryProgress> delivery(String postId) {
        return social.status(postId).flatMap(p -> Optional.ofNullable(p.networks().get(ACTIVITYPUB)))
                .flatMap(NetworkState::delivery);
    }

    /** The one request to bob's inbox carrying an activity of {@code type} about {@code objectId}. */
    static ReceivedRequest awaitInbox(String type, URI objectId) {
        await().atMost(WAIT).until(() -> inbox(type, objectId).size() == 1);
        return inbox(type, objectId).get(0);
    }

    static List<ReceivedRequest> inbox(String type, URI objectId) {
        return fake.received().stream()
                .filter(r -> r.method().equals("POST") && r.path().equals("/users/bob/inbox"))
                .filter(r -> {
                    JsonNode activity = json(r);
                    return type.equals(activity.path("type").asText())
                            && objectId.toString().equals(activity.path("object").path("id").asText());
                })
                .toList();
    }

    static JsonNode json(ReceivedRequest request) {
        try {
            return ASJson.mapper().readTree(request.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A free port chosen once (the test class is loaded twice; both loads must agree). */
    static int reservedPort() {
        // the build reserves one free port per module (root pom); a system property outranks overrideConfigKey
        Integer reserved = Integer.getInteger("quarkus.http.test-port");
        if (reserved != null) {
            return reserved;
        }
        String key = "social.activitypub.test.port";
        synchronized (System.getProperties()) {
            String existing = System.getProperty(key);
            if (existing != null) {
                return Integer.parseInt(existing);
            }
            try (ServerSocket socket = new ServerSocket(0)) {
                System.setProperty(key, String.valueOf(socket.getLocalPort()));
                return socket.getLocalPort();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Registers the local actors {@code alice} and {@code carol} at startup. */
    @ApplicationScoped
    public static class Alice {
        @Inject
        LocalActors actors;

        void register(@Observes StartupEvent event) {
            actors.register("alice", b -> {
            });
            actors.register("carol", b -> {
            });
        }
    }

    /** Records the progress events and the activity ids of the submitted deliveries. */
    @Singleton
    public static class Observed {
        final List<SocialDeliveryProgress> progress = new CopyOnWriteArrayList<>();
        final List<URI> submitted = new CopyOnWriteArrayList<>();
        volatile URI blockOn;
        volatile CountDownLatch gate;

        void on(@Observes SocialDeliveryProgress event) {
            progress.add(event);
        }

        /** Holds the publishing thread inside the submission of {@code activityId} until the latch opens. */
        void on(@Observes DeliveriesSubmitted event) throws InterruptedException {
            submitted.add(event.activityId());
            CountDownLatch latch = gate;
            if (latch != null && event.activityId().equals(blockOn)) {
                latch.await(60, TimeUnit.SECONDS);
            }
        }

        CountDownLatch block(URI activityId) {
            blockOn = activityId;
            gate = new CountDownLatch(1);
            return gate;
        }

        List<DeliveryProgress> progress(String postId) {
            return progress.stream().filter(e -> e.postId().equals(postId)).map(SocialDeliveryProgress::progress)
                    .toList();
        }

        List<URI> submitted() {
            return List.copyOf(submitted);
        }
    }
}
