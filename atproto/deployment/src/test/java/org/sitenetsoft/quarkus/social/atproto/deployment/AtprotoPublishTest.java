package org.sitenetsoft.quarkus.social.atproto.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sitenetsoft.quarkus.social.Network.ATPROTO;
import static org.sitenetsoft.quarkus.social.atproto.deployment.AtprotoApps.ANN;
import static org.sitenetsoft.quarkus.social.atproto.deployment.AtprotoApps.BEN;
import static org.sitenetsoft.quarkus.social.test.SocialAssertions.assertFailed;
import static org.sitenetsoft.quarkus.social.test.SocialAssertions.assertPublished;
import static org.sitenetsoft.quarkus.social.test.SocialAssertions.assertSkipped;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.atproto.syntax.AtUri;
import org.sitenetsoft.atproto.syntax.Tid;
import org.sitenetsoft.quarkus.atproto.oauth.AtprotoSessions;
import org.sitenetsoft.quarkus.atproto.oauth.link.AtprotoAccountLinks;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSession;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSessionStore;
import org.sitenetsoft.quarkus.atproto.test.FakeAccount;
import org.sitenetsoft.quarkus.atproto.test.FakeAtproto;
import org.sitenetsoft.quarkus.social.Capabilities;
import org.sitenetsoft.quarkus.social.FailureCode;
import org.sitenetsoft.quarkus.social.Kind;
import org.sitenetsoft.quarkus.social.NetworkResult;
import org.sitenetsoft.quarkus.social.Post;
import org.sitenetsoft.quarkus.social.SkipReason;
import org.sitenetsoft.quarkus.social.Social;
import org.sitenetsoft.quarkus.social.Visibility;
import org.sitenetsoft.quarkus.social.atproto.AtprotoSocialAdapter;
import org.sitenetsoft.quarkus.social.atproto.ScopeAdvisor;
import org.sitenetsoft.quarkus.social.content.PostContent;
import org.sitenetsoft.quarkus.social.spi.AdapterRequest;
import org.sitenetsoft.quarkus.social.store.NetworkState;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * Publishing through the adapter to the fake AT Protocol network: the application user {@code ann} linked the account
 * {@code alice.bsky.social} ({@code link} mode).
 */
public class AtprotoPublishTest {

    static final String HANDLE = "alice.bsky.social";
    static final String BOB_HANDLE = "bob.example.test";
    static final int[] PORTS = FakeAtproto.reservePorts(AtprotoPublishTest.class.getName() + ".ports");
    static final FakeAtproto FAKE = FakeAtproto.start(PORTS[0], PORTS[1], PORTS[2]);
    static final FakeAccount ALICE = FAKE.account(HANDLE);
    static final FakeAccount BOB = FAKE.account(BOB_HANDLE);
    static final Duration WAIT = Duration.ofSeconds(20);

    @RegisterExtension
    static final QuarkusExtensionTest app = AtprotoApps.linkMode(FAKE, List.of(HANDLE, BOB_HANDLE),
            HookedPublications.class)
            .setLogRecordPredicate(r -> r.getLoggerName().equals(ScopeAdvisor.class.getName()))
            // the configured scope covers the default output: no advice
            .assertLogRecords(records -> assertEquals(List.of(), records.stream().map(r -> r.getMessage()).toList()));

    @Inject
    Social social;
    @Inject
    AtprotoSocialAdapter adapter;
    @Inject
    AtprotoSessions sessions;
    @Inject
    AtprotoSessionStore sessionStore;
    @Inject
    AtprotoAccountLinks links;

    @BeforeEach
    void annLinksAlice() {
        AtprotoApps.link(ANN, HANDLE);
    }

    @AfterEach
    void reset() {
        HookedPublications.NEXT.set(null);
        FAKE.clear();
        for (FakeAccount a : List.of(ALICE, BOB)) {
            sessionStore.findByDid(a.did()).forEach(s -> sessionStore.delete(s.id()));
            for (String user : List.of(ANN, BEN)) {
                links.unlink(user, a.did());
            }
        }
    }

    @Test
    void capabilities() {
        assertEquals(new Capabilities(EnumSet.of(Visibility.PUBLIC), EnumSet.of(Kind.NOTE, Kind.ARTICLE), true, true,
                false, OptionalInt.of(300), false, false), adapter.capabilities());
    }

    @Test
    void postAndDocumentWritten() {
        Post post = AtprotoApps.article(newId()).build();
        NetworkResult.Published published = assertPublished(social.publish(ANN, post), ATPROTO);
        assertEquals(Set.of("post.uri", "post.cid", "document.uri", "document.cid"), published.refs().keySet());
        AtUri postUri = AtUri.parse(published.refs().get("post.uri"));
        AtUri docUri = AtUri.parse(published.refs().get("document.uri"));
        assertEquals(ALICE.did(), postUri.authority());
        assertEquals("app.bsky.feed.post", postUri.collection().orElseThrow().value());
        assertEquals("site.standard.document", docUri.collection().orElseThrow().value());
        String rkey = postUri.rkey().orElseThrow().value();
        assertEquals(AtprotoApps.PUBLISHED.truncatedTo(ChronoUnit.MICROS), Tid.parse(rkey).timestamp(),
                "the record key is a TID of the publication time");
        assertEquals(Optional.of(URI.create("https://bsky.app/profile/" + ALICE.did() + "/post/" + rkey)),
                published.url());

        Map<AtUri, JsonNode> records = FAKE.pds().records();
        assertEquals(2, records.size(), records::toString);
        JsonNode bsky = records.get(postUri);
        assertTrue(bsky.path("text").asText().startsWith("Hello world\n\n"), bsky::toString);
        assertEquals("https://blog.example/hello", bsky.path("embed").path("external").path("uri").asText());
        assertEquals("app.bsky.embed.external", bsky.path("embed").path("$type").asText());
        JsonNode doc = records.get(docUri);
        assertEquals(postUri.toString(), doc.path("bskyPostRef").path("uri").asText());
        assertEquals(published.refs().get("post.cid"), doc.path("bskyPostRef").path("cid").asText());
        assertEquals("https://blog.example", doc.path("site").asText());
        assertEquals("/hello", doc.path("path").asText());
    }

    @Test
    void notePublishesPostOnly() {
        Post post = Post.builder(newId()).text("Hello #bluesky").publishedAt(AtprotoApps.PUBLISHED).build();
        NetworkResult.Published published = assertPublished(social.publish(ANN, post), ATPROTO);
        assertEquals(Set.of("post.uri", "post.cid"), published.refs().keySet());
        assertEquals(1, FAKE.pds().records().size());
    }

    @Test
    void documentFailureThenRetryWritesOnlyDocument() {
        Post post = AtprotoApps.article(newId()).build();
        // after the post is written: the PDS refuses the token and the token endpoint is down
        HookedPublications.NEXT.set(() -> {
            FAKE.pds().expireTokens();
            FAKE.as().tokenEndpointStatus(503);
        });
        NetworkResult.Failed failed = assertFailed(social.publish(ANN, post), ATPROTO, FailureCode.UNAVAILABLE);
        assertTrue(failed.retryable());
        assertEquals(Set.of("post.uri", "post.cid"), failed.refs().keySet());
        assertEquals(1, FAKE.pds().records().size());

        FAKE.as().tokenEndpointStatus(200);
        NetworkResult.Published published = assertPublished(social.publish(ANN, post), ATPROTO);
        assertEquals(2, FAKE.pds().records().size(), "exactly one more record: the document");
        assertEquals(failed.refs().get("post.uri"), published.refs().get("post.uri"));
        JsonNode doc = FAKE.pds().records().get(AtUri.parse(published.refs().get("document.uri")));
        assertEquals(failed.refs().get("post.uri"), doc.path("bskyPostRef").path("uri").asText());
    }

    @Test
    void retryAfterTimedOutCreateWritesNothing() {
        Post post = AtprotoApps.article(newId()).build();
        NetworkResult.Published first = assertInstanceOf(NetworkResult.Published.class,
                adapter.publish(request(post, Optional.empty())).await().atMost(WAIT));
        assertEquals(2, FAKE.pds().records().size());
        // the engine never saw that answer: the stored state is a failure without refs
        NetworkState lost = new NetworkState(ATPROTO, NetworkState.Status.FAILED, Map.of(),
                Optional.of(FailureCode.TIMEOUT.name()), Optional.empty(), Instant.now(), Optional.empty());
        NetworkResult.Published again = assertInstanceOf(NetworkResult.Published.class,
                adapter.publish(request(post, Optional.of(lost))).await().atMost(WAIT));
        assertEquals(first.refs(), again.refs());
        assertEquals(first.url(), again.url());
        assertFalse(again.resent());
        assertEquals(2, FAKE.pds().records().size());
    }

    @Test
    void retryWhilePublishRunsIsInProgress() throws Exception {
        Post post = AtprotoApps.article(newId()).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HookedPublications.NEXT.set(() -> {
            entered.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        CompletableFuture<NetworkResult> first = adapter.publish(request(post, Optional.empty()))
                .subscribeAsCompletionStage();
        assertTrue(entered.await(20, TimeUnit.SECONDS));
        NetworkResult.Failed second = assertInstanceOf(NetworkResult.Failed.class,
                adapter.publish(request(post, Optional.empty())).await().atMost(WAIT));
        assertEquals(FailureCode.IN_PROGRESS, second.code());
        assertTrue(second.retryable());
        release.countDown();
        assertInstanceOf(NetworkResult.Published.class, first.get(20, TimeUnit.SECONDS));
        assertEquals(2, FAKE.pds().records().size());
    }

    @Test
    void previousUrlRefNotEchoed() {
        Post post = AtprotoApps.article(newId()).build();
        NetworkResult.Published first = assertInstanceOf(NetworkResult.Published.class,
                adapter.publish(request(post, Optional.empty())).await().atMost(WAIT));
        Map<String, String> refs = new java.util.HashMap<>(first.refs());
        refs.remove("document.uri");
        refs.remove("document.cid");
        refs.put(NetworkState.URL_REF, first.url().orElseThrow().toString());
        NetworkState partial = new NetworkState(ATPROTO, NetworkState.Status.FAILED, refs,
                Optional.of(FailureCode.UNAVAILABLE.name()), Optional.empty(), Instant.now(), Optional.empty());
        NetworkResult.Published again = assertInstanceOf(NetworkResult.Published.class,
                adapter.publish(request(post, Optional.of(partial))).await().atMost(WAIT));
        assertFalse(again.refs().containsKey(NetworkState.URL_REF), again.refs()::toString);
    }

    @Test
    void followersOnlySkipped() {
        Post post = Post.builder(newId()).text("For followers").visibility(Visibility.FOLLOWERS).build();
        assertSkipped(social.publish(ANN, post), ATPROTO, SkipReason.VISIBILITY_NOT_SUPPORTED);
        assertEquals(0, FAKE.pds().records().size());
    }

    @Test
    void contentWarningSkippedByDefault() {
        assertSkipped(social.publish(ANN, Post.builder(newId()).text("Spoilers").contentWarning("film").build()),
                ATPROTO, SkipReason.CONTENT_WARNING_NOT_SUPPORTED);
        assertSkipped(social.publish(ANN, Post.builder(newId()).text("Sensitive").sensitive(true).build()),
                ATPROTO, SkipReason.CONTENT_WARNING_NOT_SUPPORTED);
        assertEquals(0, FAKE.pds().records().size());
    }

    @Test
    void contentWarningAddedOnUpdateFails() {
        Post post = AtprotoApps.article(newId()).build();
        assertPublished(social.publish(ANN, post), ATPROTO);
        Map<AtUri, JsonNode> before = FAKE.pds().records();
        NetworkResult.Failed failed = assertFailed(social.update(ANN, AtprotoApps.article(post.id()).contentWarning("film")
                .build()), ATPROTO, FailureCode.CONTENT_WARNING_NOT_SUPPORTED);
        assertFalse(failed.retryable());
        assertTrue(failed.reason().contains("the existing Bluesky copy is unchanged"), failed.reason());
        assertEquals(before, FAKE.pds().records());
    }

    @Test
    void updatePutsDocumentKeepsPost() {
        Post post = AtprotoApps.article(newId()).build();
        NetworkResult.Published published = assertPublished(social.publish(ANN, post), ATPROTO);
        AtUri postUri = AtUri.parse(published.refs().get("post.uri"));
        AtUri docUri = AtUri.parse(published.refs().get("document.uri"));
        JsonNode postBefore = FAKE.pds().records().get(postUri);

        NetworkResult.Published updated = assertPublished(social.update(ANN, AtprotoApps.article(post.id())
                .title("Edited title").build()), ATPROTO);
        assertEquals(Optional.of("Bluesky post not edited"), updated.note());
        assertEquals(published.url(), updated.url());
        String newCid = updated.refs().get("document.cid");
        assertNotEquals(published.refs().get("document.cid"), newCid);
        assertEquals(Optional.of(newCid), FAKE.pds().cid(docUri));
        assertEquals(postBefore, FAKE.pds().records().get(postUri));
        JsonNode doc = FAKE.pds().records().get(docUri);
        assertEquals("Edited title", doc.path("title").asText());
        assertTrue(doc.has("updatedAt"), doc::toString);
        assertEquals(postUri.toString(), doc.path("bskyPostRef").path("uri").asText());
        assertEquals(2, FAKE.pds().records().size());
        // the stored state has the new CID: a second edit swaps on it
        assertPublished(social.update(ANN, AtprotoApps.article(post.id()).title("Edited again").build()), ATPROTO);
        assertEquals("Edited again", FAKE.pds().records().get(docUri).path("title").asText());
    }

    @Test
    void deleteRemovesBoth() {
        Post post = AtprotoApps.article(newId()).build();
        assertPublished(social.publish(ANN, post), ATPROTO);
        assertEquals(2, FAKE.pds().records().size());
        assertPublished(social.delete(ANN, post.id()), ATPROTO);
        assertEquals(0, FAKE.pds().records().size());
    }

    @Test
    void expiredSessionFails() {
        sessions.revoke(ALICE.did());
        NetworkResult.Failed failed = assertFailed(social.publish(ANN, Post.builder(newId()).text("Hello").build()),
                ATPROTO, FailureCode.SESSION_EXPIRED);
        assertFalse(failed.retryable());
        assertEquals("sign in to Bluesky again", failed.reason());
    }

    @Test
    void missingScopeFails() {
        // the account granted only the post scope
        for (AtprotoSession s : sessionStore.findByDid(ALICE.did())) {
            sessionStore.save(s.withTokens(s.accessToken(), s.refreshToken(), s.accessExpiry(),
                    "atproto repo:app.bsky.feed.post?action=create&action=delete", s.nonces()));
        }
        NetworkResult.Failed failed = assertFailed(social.publish(ANN, AtprotoApps.article(newId()).build()), ATPROTO,
                FailureCode.SCOPE_MISSING);
        assertFalse(failed.retryable());
        assertEquals(Set.of("post.uri", "post.cid"), failed.refs().keySet());
        assertEquals(1, FAKE.pds().records().size());
    }

    @Test
    void notLinkedSkipped() {
        assertSkipped(social.publish(BEN, Post.builder(newId()).text("Hello").build()), ATPROTO,
                SkipReason.NOT_LINKED);
    }

    @Test
    void severalLinkedRejected() {
        AtprotoApps.link(ANN, BOB_HANDLE);
        NetworkResult.Failed failed = assertFailed(social.publish(ANN, Post.builder(newId()).text("Hello").build()),
                ATPROTO, FailureCode.REJECTED);
        assertTrue(failed.reason().contains("several Bluesky accounts linked; provide SocialAccountLinks"),
                failed.reason());
        assertEquals(0, FAKE.pds().records().size());
    }

    static AdapterRequest request(Post post, Optional<NetworkState> previous) {
        return new AdapterRequest(ANN, ALICE.did().value(), post, PostContent.of(post), previous);
    }

    static String newId() {
        return "post-" + UUID.randomUUID();
    }
}
