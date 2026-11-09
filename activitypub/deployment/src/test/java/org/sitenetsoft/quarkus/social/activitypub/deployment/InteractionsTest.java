package org.sitenetsoft.quarkus.social.activitypub.deployment;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.function.Predicate;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.activitystreams.ASObject;
import org.sitenetsoft.activitystreams.Activity;
import org.sitenetsoft.activitystreams.Announce;
import org.sitenetsoft.activitystreams.Create;
import org.sitenetsoft.activitystreams.Follow;
import org.sitenetsoft.activitystreams.Like;
import org.sitenetsoft.activitystreams.Note;
import org.sitenetsoft.activitystreams.ObjectOrLink;
import org.sitenetsoft.activitystreams.Undo;
import org.sitenetsoft.httpsignatures.Scheme;
import org.sitenetsoft.quarkus.activitypub.actor.LocalActors;
import org.sitenetsoft.quarkus.activitypub.test.FakeActor;
import org.sitenetsoft.quarkus.activitypub.test.FakeFediverseServer;
import org.sitenetsoft.quarkus.social.Post;
import org.sitenetsoft.quarkus.social.Social;
import org.sitenetsoft.quarkus.social.events.ReplyAudience;
import org.sitenetsoft.quarkus.social.events.SocialFollow;
import org.sitenetsoft.quarkus.social.events.SocialReaction;
import org.sitenetsoft.quarkus.social.events.SocialReactionUndone;
import org.sitenetsoft.quarkus.social.events.SocialReply;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.QuarkusExtensionTest;

/** Inbound replies, likes, reposts and follows from a fake Mastodon become neutral events. */
public class InteractionsTest {

    static final int PORT = reservedPort();
    static final String BASE = "http://127.0.0.1:" + PORT;
    static final URI INBOX = URI.create(BASE + "/users/alice/inbox");
    static final Duration WAIT = Duration.ofSeconds(20);

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Alice.class, Seen.class))
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
    void replyToPublishedPostFiresSocialReply() {
        Published post = publish();
        FakeActor rita = fake.actor("rita");
        Note reply = note(rita, post.objectId);
        reply.content("<p>nice</p><script>alert(1)</script><img src=x onerror=alert(2)>"
                + "<a href=\"javascript:alert(3)\">bad</a><a href=\"https://blog.example/x\">good</a>");
        URI replyUrl = rita.id().resolve("/@rita/1");
        reply.url(ObjectOrLink.of(replyUrl));
        assertEquals(202, rita.post(INBOX, create(rita, reply), Scheme.CAVAGE).statusCode());

        SocialReply event = seenIn(seen.replies, r -> r.author().id().equals(rita.id().toString()));
        assertEquals(post.postId, event.postId());
        assertEquals("alice", event.account());
        assertEquals(ACTIVITYPUB, event.network());
        assertEquals(java.util.Optional.of(replyUrl), event.url());
        assertEquals(ReplyAudience.DIRECT, event.audience());
        String html = event.html().toLowerCase();
        assertFalse(html.contains("<script"), html);
        assertFalse(html.contains("alert(1)"), html);
        assertFalse(html.contains("onerror"), html);
        assertFalse(html.contains("javascript:"), html);
        assertTrue(html.contains("nice"), html);
        assertTrue(html.contains("href=\"https://blog.example/x\""), html);
        assertTrue(html.contains("nofollow"), html);
    }

    @Test
    void replyUrlOnAnotherHostIsDropped() {
        Published post = publish();
        FakeActor ron = fake.actor("ron");
        Note reply = note(ron, post.objectId);
        reply.url(ObjectOrLink.of(URI.create("https://ap-test.example.net/@ron/1")));
        assertEquals(202, ron.post(INBOX, create(ron, reply), Scheme.CAVAGE).statusCode());
        SocialReply event = seenIn(seen.replies, r -> r.author().id().equals(ron.id().toString()));
        assertEquals(java.util.Optional.of(reply.id()), event.url());
    }

    @Test
    void createAttributedToSomeoneElseFiresNothing() {
        Published post = publish();
        FakeActor sam = fake.actor("sam");
        FakeActor victim = fake.actor("victim");
        Note forged = note(sam, post.objectId);
        forged.attributedTo(ObjectOrLink.of(victim.id()));
        assertEquals(202, sam.post(INBOX, create(sam, forged), Scheme.CAVAGE).statusCode());
        // processed in order for one sender: the like after it proves the forged create is done
        assertEquals(202, sam.post(INBOX, like(sam, post.objectId), Scheme.CAVAGE).statusCode());
        seenIn(seen.reactions, r -> r.author().id().equals(sam.id().toString()));
        assertTrue(seen.replies.stream().noneMatch(r -> r.postId().equals(post.postId)
                && (r.author().id().equals(sam.id().toString()) || r.author().id().equals(victim.id().toString()))));
    }

    @Test
    void replyAudiencesAreDerived() {
        Published post = publish();
        URI pub = URI.create("https://www.w3.org/ns/activitystreams#Public");
        assertEquals(ReplyAudience.PUBLIC, audienceOf(post, "pat", n -> n.to(ObjectOrLink.of(pub))));
        assertEquals(ReplyAudience.UNLISTED, audienceOf(post, "uli", n -> n.cc(ObjectOrLink.of(pub))));
        assertEquals(ReplyAudience.FOLLOWERS_ONLY, audienceOf(post, "fol",
                n -> n.to(ObjectOrLink.of(URI.create(n.attributedTo().get(0).uri().get() + "/followers")))));
        assertEquals(ReplyAudience.DIRECT, audienceOf(post, "dim", n -> {
        }));
    }

    private ReplyAudience audienceOf(Published post, String name, java.util.function.Consumer<Note> addressing) {
        FakeActor from = fake.actor(name);
        Note reply = note(from, post.objectId);
        reply.to(new ObjectOrLink[0]);
        addressing.accept(reply);
        if (reply.to().isEmpty() && reply.cc().isEmpty()) {
            reply.to(ObjectOrLink.of(URI.create(BASE + "/users/alice")));
        }
        Create create = create(from, reply);
        create.to(reply.to().toArray(ObjectOrLink[]::new));
        assertEquals(202, from.post(INBOX, create, Scheme.CAVAGE).statusCode());
        return seenIn(seen.replies, r -> r.author().id().equals(from.id().toString())).audience();
    }

    @Test
    void replyToUnknownObjectFiresNothing() {
        FakeActor ula = fake.actor("ula");
        Note reply = note(ula, URI.create(BASE + "/objects/unknown"));
        assertEquals(202, ula.post(INBOX, create(ula, reply), Scheme.CAVAGE).statusCode());
        // a later, matching activity from the same actor is processed after it: nothing may have fired for ula
        Published post = publish();
        Like like = like(ula, post.objectId);
        assertEquals(202, ula.post(INBOX, like, Scheme.CAVAGE).statusCode());
        seenIn(seen.reactions, r -> r.author().id().equals(ula.id().toString()));
        assertTrue(seen.replies.stream().noneMatch(r -> r.author().id().equals(ula.id().toString())));
    }

    @Test
    void likeFiresReaction() {
        Published post = publish();
        FakeActor lena = fake.actor("lena");
        assertEquals(202, lena.post(INBOX, like(lena, post.objectId), Scheme.CAVAGE).statusCode());
        SocialReaction event = seenIn(seen.reactions, r -> r.author().id().equals(lena.id().toString()));
        assertEquals(SocialReaction.Type.LIKE, event.type());
        assertEquals(post.postId, event.postId());
        assertEquals("alice", event.account());
        assertEquals(ACTIVITYPUB, event.author().network());
    }

    @Test
    void announceFiresRepost() {
        Published post = publish();
        FakeActor bea = fake.actor("bea");
        Announce announce = new Announce();
        announce.id(id(bea));
        announce.actor(ObjectOrLink.of(bea.id())).object(ObjectOrLink.of(post.objectId));
        assertEquals(202, bea.post(INBOX, announce, Scheme.CAVAGE).statusCode());
        SocialReaction event = seenIn(seen.reactions, r -> r.author().id().equals(bea.id().toString()));
        assertEquals(SocialReaction.Type.REPOST, event.type());
        assertEquals(post.postId, event.postId());
    }

    @Test
    void undoLikeFiresUndone() {
        Published post = publish();
        FakeActor uma = fake.actor("uma");
        Like like = like(uma, post.objectId);
        assertEquals(202, uma.post(INBOX, like, Scheme.CAVAGE).statusCode());
        seenIn(seen.reactions, r -> r.author().id().equals(uma.id().toString()));
        Undo undo = new Undo();
        undo.id(id(uma));
        undo.actor(ObjectOrLink.of(uma.id())).object(ObjectOrLink.of(like));
        assertEquals(202, uma.post(INBOX, undo, Scheme.CAVAGE).statusCode());
        SocialReactionUndone event = seenIn(seen.undone, r -> r.author().id().equals(uma.id().toString()));
        assertEquals(SocialReaction.Type.LIKE, event.type());
        assertEquals(post.postId, event.postId());
    }

    @Test
    void undoLikeByBareUriFiresNothing() {
        Published post = publish();
        FakeActor ben = fake.actor("ben");
        Like like = like(ben, post.objectId);
        assertEquals(202, ben.post(INBOX, like, Scheme.CAVAGE).statusCode());
        Undo undo = new Undo();
        undo.id(id(ben));
        undo.actor(ObjectOrLink.of(ben.id())).object(ObjectOrLink.of(like.id()));
        assertEquals(202, ben.post(INBOX, undo, Scheme.CAVAGE).statusCode());
        // a follow sent afterwards by the same actor is processed after the undo
        assertEquals(202, ben.post(INBOX, follow(ben), Scheme.CAVAGE).statusCode());
        seenIn(seen.follows, f -> f.author().id().equals(ben.id().toString()));
        assertTrue(seen.undone.stream().noneMatch(r -> r.author().id().equals(ben.id().toString())));
    }

    @Test
    void followFiresSocialFollowAccepted() {
        FakeActor fay = fake.actor("fay");
        assertEquals(202, fay.post(INBOX, follow(fay), Scheme.CAVAGE).statusCode());
        SocialFollow event = seenIn(seen.follows, f -> f.author().id().equals(fay.id().toString()));
        assertEquals("alice", event.account());
        assertEquals(SocialFollow.State.ACCEPTED, event.state());
        assertEquals(ACTIVITYPUB, event.network());
    }

    @Test
    void undoFollowFiresUndone() {
        FakeActor ugo = fake.actor("ugo");
        Follow follow = follow(ugo);
        assertEquals(202, ugo.post(INBOX, follow, Scheme.CAVAGE).statusCode());
        seenIn(seen.follows, f -> f.author().id().equals(ugo.id().toString()));
        Undo undo = new Undo();
        undo.id(id(ugo));
        undo.actor(ObjectOrLink.of(ugo.id())).object(ObjectOrLink.of(follow));
        assertEquals(202, ugo.post(INBOX, undo, Scheme.CAVAGE).statusCode());
        seenIn(seen.follows, f -> f.author().id().equals(ugo.id().toString()) && f.state() == SocialFollow.State.UNDONE);
    }

    @Test
    void observerFailureDoesNotBreakInbox() {
        Published post = publish();
        FakeActor oli = fake.actor("oli");
        Seen.failOnReaction = true;
        try {
            assertEquals(202, oli.post(INBOX, like(oli, post.objectId), Scheme.CAVAGE).statusCode());
            seenIn(seen.reactions, r -> r.author().id().equals(oli.id().toString()));
        } finally {
            Seen.failOnReaction = false;
        }
        // the inbox still works afterwards
        FakeActor ola = fake.actor("ola");
        assertEquals(202, ola.post(INBOX, like(ola, post.objectId), Scheme.CAVAGE).statusCode());
        seenIn(seen.reactions, r -> r.author().id().equals(ola.id().toString()));
    }

    // ---- helpers ----

    record Published(String postId, URI objectId) {
    }

    Published publish() {
        Post post = Post.builder(UUID.randomUUID().toString()).text("Hello").build();
        var published = assertPublished(social.publish("alice", post), ACTIVITYPUB);
        return new Published(post.id(), URI.create(published.refs().get("object")));
    }

    static URI id(FakeActor from) {
        return URI.create(from.id().resolve("/") + "activities/" + UUID.randomUUID());
    }

    static Note note(FakeActor from, URI inReplyTo) {
        Note note = new Note();
        note.id(URI.create(from.id().resolve("/") + "objects/" + UUID.randomUUID()));
        note.attributedTo(ObjectOrLink.of(from.id()));
        note.content("<p>hello</p>");
        note.inReplyTo(ObjectOrLink.of(inReplyTo));
        note.to(ObjectOrLink.of(URI.create(BASE + "/users/alice")));
        return note;
    }

    static Create create(FakeActor from, ASObject object) {
        Create create = new Create();
        create.id(id(from));
        create.actor(ObjectOrLink.of(from.id())).object(ObjectOrLink.of(object));
        create.to(object.to().toArray(ObjectOrLink[]::new));
        return create;
    }

    static Like like(FakeActor from, URI object) {
        Like like = new Like();
        like.id(id(from));
        like.actor(ObjectOrLink.of(from.id())).object(ObjectOrLink.of(object));
        return like;
    }

    static Follow follow(FakeActor from) {
        Follow follow = new Follow();
        follow.id(id(from));
        follow.actor(ObjectOrLink.of(from.id())).object(ObjectOrLink.of(URI.create(BASE + "/users/alice")));
        return follow;
    }

    static <T> T seenIn(List<T> list, Predicate<T> match) {
        await().atMost(WAIT).until(() -> list.stream().anyMatch(match));
        return list.stream().filter(match).findFirst().orElseThrow();
    }

    /** One port for both class loaders QuarkusExtensionTest loads the class in. */
    static int reservedPort() {
        // the build reserves one free port per module (root pom); a system property outranks overrideConfigKey
        Integer reserved = Integer.getInteger("quarkus.http.test-port");
        if (reserved != null) {
            return reserved;
        }
        String key = "social.activitypub.interactions.port";
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
        static volatile boolean failOnReaction;
        final List<SocialReply> replies = new CopyOnWriteArrayList<>();
        final List<SocialReaction> reactions = new CopyOnWriteArrayList<>();
        final List<SocialReactionUndone> undone = new CopyOnWriteArrayList<>();
        final List<SocialFollow> follows = new CopyOnWriteArrayList<>();

        void on(@Observes SocialReply e) {
            replies.add(e);
        }

        void on(@Observes SocialReaction e) {
            reactions.add(e);
            if (failOnReaction) {
                throw new IllegalStateException("observer boom");
            }
        }

        void on(@Observes SocialReactionUndone e) {
            undone.add(e);
        }

        void on(@Observes SocialFollow e) {
            follows.add(e);
        }
    }
}
