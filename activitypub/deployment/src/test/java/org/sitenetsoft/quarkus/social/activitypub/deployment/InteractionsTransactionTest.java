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

import java.util.function.Supplier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.annotation.Priority;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.transaction.TransactionSynchronizationRegistry;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.activitystreams.Follow;
import org.sitenetsoft.activitystreams.ObjectOrLink;
import org.sitenetsoft.httpsignatures.Scheme;
import org.sitenetsoft.quarkus.activitypub.actor.LocalActors;
import org.sitenetsoft.quarkus.activitypub.inbox.Inbound;
import org.sitenetsoft.quarkus.activitypub.store.FollowState;
import org.sitenetsoft.quarkus.activitypub.store.Stores;
import org.sitenetsoft.quarkus.activitypub.store.TransactionRunner;
import org.sitenetsoft.quarkus.activitypub.test.FakeActor;
import org.sitenetsoft.quarkus.activitypub.test.FakeFediverseServer;
import org.sitenetsoft.quarkus.social.events.SocialFollow;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.QuarkusExtensionTest;

/** Social events fire after the inbox's JTA transaction commits, and not at all when it rolls back. */
public class InteractionsTransactionTest {

    static final int PORT = reservedPort();
    static final String BASE = "http://127.0.0.1:" + PORT;
    static final URI ALICE = URI.create(BASE + "/users/alice");
    static final URI INBOX = URI.create(BASE + "/users/alice/inbox");
    static final Duration WAIT = Duration.ofSeconds(20);

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Alice.class, Seen.class, JtaRunner.class, Poison.class))
            .overrideConfigKey("quarkus.http.test-port", String.valueOf(PORT))
            .overrideConfigKey("quarkus.activitypub.base-url", BASE)
            .overrideConfigKey("quarkus.safe-fetch.https-only", "false")
            .overrideConfigKey("quarkus.safe-fetch.allow-private-addresses", "true");

    static final FakeFediverseServer fake = new FakeFediverseServer();

    @Inject
    Seen seen;
    @Inject
    Stores stores;

    @BeforeAll
    static void startFake() {
        fake.start();
    }

    @AfterAll
    static void stopFake() {
        fake.stop();
    }

    @Test
    void failingTransactionalObserverDoesNotRollBackTheInbox() {
        FakeActor tia = fake.actor("tia");
        assertEquals(202, tia.post(INBOX, follow(tia), Scheme.CAVAGE).statusCode());
        await().atMost(WAIT).until(() -> seen.follows.stream().anyMatch(f -> f.author().id().equals(tia.id().toString())));
        assertEquals(SocialFollow.State.ACCEPTED, seen.follows.stream()
                .filter(f -> f.author().id().equals(tia.id().toString())).findFirst().orElseThrow().state());
        assertEquals(FollowState.ACCEPTED, stores.followers().follower(ALICE, tia.id()).orElseThrow().state());
    }

    @Test
    void rolledBackInboxFiresNoSocialEvent() throws InterruptedException {
        FakeActor rob = fake.actor("rob");
        Poison.victim = rob.id();
        assertEquals(202, rob.post(INBOX, follow(rob), Scheme.CAVAGE).statusCode());
        await().atMost(WAIT).until(() -> Poison.hit);
        FakeActor ned = fake.actor("ned");
        assertEquals(202, ned.post(INBOX, follow(ned), Scheme.CAVAGE).statusCode());
        await().atMost(WAIT).until(() -> seen.follows.stream().anyMatch(f -> f.author().id().equals(ned.id().toString())));
        Thread.sleep(1000);
        assertTrue(seen.follows.stream().noneMatch(f -> f.author().id().equals(rob.id().toString())));
    }

    static Follow follow(FakeActor from) {
        Follow follow = new Follow();
        follow.id(URI.create(from.id().resolve("/") + "activities/" + UUID.randomUUID()));
        follow.actor(ObjectOrLink.of(from.id())).object(ObjectOrLink.of(ALICE));
        return follow;
    }

    static int reservedPort() {
        // the build reserves one free port per module (root pom); a system property outranks overrideConfigKey
        Integer reserved = Integer.getInteger("quarkus.http.test-port");
        if (reserved != null) {
            return reserved;
        }
        String key = "social.activitypub.tx.port";
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

    /** The inbox runs its handlers in a JTA transaction, as with the Hibernate stores. */
    @ApplicationScoped
    public static class JtaRunner implements TransactionRunner {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return QuarkusTransaction.joiningExisting().call(work::get);
        }
    }

    /** Fails like a {@code @Transactional} observer: joins the caller's transaction (REQUIRED) and throws. */
    @Singleton
    public static class Seen {
        final List<SocialFollow> follows = new CopyOnWriteArrayList<>();

        void on(@Observes SocialFollow e) {
            follows.add(e);
            QuarkusTransaction.joiningExisting().run(() -> {
                throw new IllegalStateException("observer boom");
            });
        }
    }

    /** Runs after InboundInteractions and marks the inbox transaction rollback-only for one actor. */
    @Singleton
    public static class Poison {
        static volatile URI victim;
        static volatile boolean hit;
        @Inject
        TransactionSynchronizationRegistry registry;

        void on(@Observes @Inbound @Priority(5000) Follow follow) {
            if (follow.actorUri().filter(a -> a.equals(victim)).isPresent()) {
                registry.setRollbackOnly();
                hit = true;
            }
        }
    }
}
