package org.sitenetsoft.quarkus.social.activitypub.deployment;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sitenetsoft.quarkus.social.Network.ACTIVITYPUB;
import static org.sitenetsoft.quarkus.social.test.SocialAssertions.assertPublished;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.activitystreams.Create;
import org.sitenetsoft.activitystreams.Note;
import org.sitenetsoft.activitystreams.ObjectOrLink;
import org.sitenetsoft.httpsignatures.Scheme;
import org.sitenetsoft.quarkus.activitypub.actor.LocalActors;
import org.sitenetsoft.quarkus.activitypub.test.FakeActor;
import org.sitenetsoft.quarkus.activitypub.test.FakeFediverseServer;
import org.sitenetsoft.quarkus.social.Network;
import org.sitenetsoft.quarkus.social.Post;
import org.sitenetsoft.quarkus.social.Social;
import org.sitenetsoft.quarkus.social.events.SocialReply;
import org.sitenetsoft.quarkus.social.store.InMemorySocialPostStore;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.QuarkusExtensionTest;

/** Reply lookups are bounded: at most 5 inReplyTo entries and 2 s per inbound activity, even with a slow store. */
public class InteractionsLimitsTest {

    static final int PORT = reservedPort();
    static final String BASE = "http://127.0.0.1:" + PORT;
    static final URI INBOX = URI.create(BASE + "/users/alice/inbox");
    static final Duration WAIT = Duration.ofSeconds(20);

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Alice.class, Seen.class, SlowStore.class))
            .overrideConfigKey("quarkus.http.test-port", String.valueOf(PORT))
            .overrideConfigKey("quarkus.activitypub.base-url", BASE)
            .overrideConfigKey("quarkus.social.timeout", "5s")
            .overrideConfigKey("quarkus.safe-fetch.https-only", "false")
            .overrideConfigKey("quarkus.safe-fetch.allow-private-addresses", "true");

    static final FakeFediverseServer fake = new FakeFediverseServer();

    @Inject
    Social social;
    @Inject
    Seen seen;

    @BeforeAll
    static void startFake() {
        fake.start();
    }

    @AfterAll
    static void stopFake() {
        fake.stop();
    }

    @Test
    void onlyTheFirstFiveInReplyToEntriesAreExamined() {
        URI object = publish();
        FakeActor fran = fake.actor("fran");
        Note beyond = reply(fran, 5, object);
        assertEquals(202, fran.post(INBOX, create(fran, beyond), Scheme.CAVAGE).statusCode());
        FakeActor fred = fake.actor("fred");
        Note within = reply(fred, 4, object);
        assertEquals(202, fred.post(INBOX, create(fred, within), Scheme.CAVAGE).statusCode());
        await().atMost(WAIT).until(() -> seen.replies.stream().anyMatch(r -> r.author().id().equals(fred.id().toString())));
        assertTrue(seen.replies.stream().noneMatch(r -> r.author().id().equals(fran.id().toString())));
    }

    @Test
    void slowStoreIsCutOffAfterTheBudget() throws InterruptedException {
        URI object = publish();
        FakeActor sly = fake.actor("sly");
        Note many = reply(sly, 19, object);
        SlowStore.delay = Duration.ofMillis(1500);
        SlowStore.calls.set(0);
        long start = System.nanoTime();
        try {
            assertEquals(202, sly.post(INBOX, create(sly, many), Scheme.CAVAGE).statusCode());
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 3000, "the inbox answers at once");
            Thread.sleep(4000);
            int calls = SlowStore.calls.get();
            assertTrue(calls >= 1 && calls <= 2, "lookups stop at the 2 s budget, calls=" + calls);
            assertTrue(seen.replies.stream().noneMatch(r -> r.author().id().equals(sly.id().toString())));
        } finally {
            SlowStore.delay = Duration.ZERO;
        }
    }

    // ---- helpers ----

    URI publish() {
        Post post = Post.builder(UUID.randomUUID().toString()).text("Hello").build();
        return URI.create(assertPublished(social.publish("alice", post), ACTIVITYPUB).refs().get("object"));
    }

    /** A reply with {@code unknown} unmatched inReplyTo entries followed by {@code object}. */
    static Note reply(FakeActor from, int unknown, URI object) {
        Note note = new Note();
        note.id(URI.create(from.id().resolve("/") + "objects/" + UUID.randomUUID()));
        note.attributedTo(ObjectOrLink.of(from.id()));
        note.content("<p>hi</p>");
        ObjectOrLink[] targets = new ObjectOrLink[unknown + 1];
        for (int i = 0; i < unknown; i++) {
            targets[i] = ObjectOrLink.of(URI.create(BASE + "/objects/unknown-" + UUID.randomUUID()));
        }
        targets[unknown] = ObjectOrLink.of(object);
        note.inReplyTo(targets);
        note.to(ObjectOrLink.of(URI.create(BASE + "/users/alice")));
        return note;
    }

    static Create create(FakeActor from, Note object) {
        Create create = new Create();
        create.id(URI.create(from.id().resolve("/") + "activities/" + UUID.randomUUID()));
        create.actor(ObjectOrLink.of(from.id())).object(ObjectOrLink.of(object));
        create.to(object.to().toArray(ObjectOrLink[]::new));
        return create;
    }

    static int reservedPort() {
        // the build reserves one free port per module (root pom); a system property outranks overrideConfigKey
        Integer reserved = Integer.getInteger("quarkus.http.test-port");
        if (reserved != null) {
            return reserved;
        }
        String key = "social.activitypub.limits.port";
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

    @Singleton
    public static class Alice {
        @Inject
        LocalActors actors;

        void register(@Observes StartupEvent event) {
            actors.register("alice", b -> {
            });
        }
    }

    @Singleton
    public static class Seen {
        final List<SocialReply> replies = new CopyOnWriteArrayList<>();

        void on(@Observes SocialReply e) {
            replies.add(e);
        }
    }

    /** The in-memory store whose ref lookup can be made slow. */
    @Singleton
    public static class SlowStore extends InMemorySocialPostStore {
        static volatile Duration delay = Duration.ZERO;
        static final AtomicInteger calls = new AtomicInteger();

        @Override
        public Optional<String> findPostIdByRef(Network network, String ref) {
            if (!delay.isZero()) {
                calls.incrementAndGet();
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.findPostIdByRef(network, ref);
        }
    }
}
