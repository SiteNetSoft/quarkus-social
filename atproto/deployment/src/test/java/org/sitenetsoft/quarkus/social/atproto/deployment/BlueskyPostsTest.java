package org.sitenetsoft.quarkus.social.atproto.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.sitenetsoft.atproto.syntax.Language;
import org.sitenetsoft.quarkus.atproto.lexicon.Graphemes;
import org.sitenetsoft.quarkus.social.FailureCode;
import org.sitenetsoft.quarkus.social.Kind;
import org.sitenetsoft.quarkus.social.NetworkResult;
import org.sitenetsoft.quarkus.social.Post;
import org.sitenetsoft.quarkus.social.atproto.BlueskyPosts;
import org.sitenetsoft.quarkus.social.content.PostContent;
import org.sitenetsoft.quarkus.social.spi.AdapterRequest;

import app.bsky.feed.Post.Embed;
import app.bsky.feed.Post.Labels;
import app.bsky.richtext.Facet;

class BlueskyPostsTest {

    static final Instant PUBLISHED = Instant.parse("2026-11-04T10:15:30.123456Z");
    static final URI URL = URI.create("https://blog.example/posts/hello");

    @Test
    void noteTextWithFacets() {
        String text = "Café 👩‍💻 https://blog.example/é #quarkus";
        app.bsky.feed.Post post = BlueskyPosts.build(request(Post.builder("n1").text(text).language("fr")
                .publishedAt(PUBLISHED).build()), TestConfig.defaults());
        assertEquals(text, post.text());
        assertEquals(List.of(Language.parse("fr")), post.langs());
        // AT Protocol datetimes carry milliseconds
        assertEquals(PUBLISHED.truncatedTo(ChronoUnit.MILLIS), post.createdAt().instant());
        assertNull(post.embed());
        assertNull(post.labels());
        assertEquals(2, post.facets().size());
        Facet link = post.facets().get(0);
        Facet tag = post.facets().get(1);
        assertEquals("https://blog.example/é", slice(text, link));
        assertEquals("#quarkus", slice(text, tag));
        assertInstanceOf(Facet.Features.Link.class, link.features().get(0));
        assertEquals("quarkus", ((Facet.Features.Tag) tag.features().get(0)).value().tag());
    }

    @Test
    void noteOver300Fails() {
        String text = "a".repeat(301);
        BlueskyPosts.Refused refused = assertThrows(BlueskyPosts.Refused.class,
                () -> BlueskyPosts.build(request(Post.builder("n2").text(text).build()), TestConfig.defaults()));
        NetworkResult.Failed failed = assertInstanceOf(NetworkResult.Failed.class, refused.result());
        assertEquals(FailureCode.TEXT_TOO_LONG, failed.code());
        assertFalse(failed.retryable());
        // exactly 300 is fine
        assertEquals(300, Graphemes.count(BlueskyPosts.build(request(Post.builder("n3").text("é".repeat(300))
                .build()), TestConfig.defaults()).text()));
    }

    @Test
    void articleTeaserTrimmedWithCard() {
        String summary = "word ".repeat(80); // 400 graphemes
        app.bsky.feed.Post post = BlueskyPosts.build(request(article("a1").summary(summary).build()),
                TestConfig.defaults());
        assertTrue(Graphemes.count(post.text()) <= 300, () -> Graphemes.count(post.text()) + " graphemes");
        assertTrue(post.text().startsWith("Hello world\n\nword word"), post.text());
        assertTrue(post.text().endsWith("…"), post.text());
        Embed.External embed = assertInstanceOf(Embed.External.class, post.embed());
        assertEquals(URL, embed.value().external().uri());
        assertEquals("Hello world", embed.value().external().title());
        assertEquals(summary, embed.value().external().description());
    }

    @Test
    void articleWithoutSummaryUsesFirstParagraph() {
        app.bsky.feed.Post post = BlueskyPosts.build(request(article("a2")
                .html("<p>First paragraph #java.</p><p>Second paragraph.</p>").build()), TestConfig.defaults());
        assertEquals("Hello world\n\nFirst paragraph #java.", post.text());
        Embed.External embed = assertInstanceOf(Embed.External.class, post.embed());
        assertEquals("", embed.value().external().description());
        assertEquals(1, post.facets().size());
        assertEquals("#java", slice(post.text(), post.facets().get(0)));
    }

    @Test
    void teaserNeverSplitsEmoji() {
        // the 👩‍💻 graphemes straddle the 300th grapheme
        String summary = "x".repeat(285) + "👩‍💻".repeat(30);
        app.bsky.feed.Post post = BlueskyPosts.build(request(article("a3").summary(summary).build()),
                TestConfig.defaults());
        assertTrue(Graphemes.count(post.text()) <= 300);
        assertTrue(post.text().endsWith("👩‍💻…"), post.text());
        String body = post.text().substring(0, post.text().length() - 1);
        assertEquals(0, body.replace("👩‍💻", "").replace("x", "").replace("Hello world\n\n", "").length(),
                "no broken emoji pieces: " + body);
    }

    @Test
    void labelSettingAddsSelfLabel() {
        Post sensitive = Post.builder("n4").text("Careful").contentWarning("gore").build();
        app.bsky.feed.Post labelled = BlueskyPosts.build(request(sensitive),
                TestConfig.defaults().withContentWarning("label:graphic-media"));
        Labels.SelfLabels labels = assertInstanceOf(Labels.SelfLabels.class, labelled.labels());
        assertEquals(List.of("graphic-media"), labels.value().values().stream().map(l -> l.val()).toList());
        // drop: no label
        assertNull(BlueskyPosts.build(request(sensitive), TestConfig.defaults().withContentWarning("drop")).labels());
        // the post's own hint wins over the setting, and applies without a warning too
        Post hinted = Post.builder("n5").text("Art").atprotoLabels(List.of("nudity")).build();
        Labels.SelfLabels own = assertInstanceOf(Labels.SelfLabels.class, BlueskyPosts.build(request(hinted),
                TestConfig.defaults().withContentWarning("label:porn")).labels());
        assertEquals(List.of("nudity"), own.value().values().stream().map(l -> l.val()).toList());
    }

    static Post.Builder article(String id) {
        return Post.builder(id).kind(Kind.ARTICLE).title("Hello world").url(URL).html("<p>Body text.</p>")
                .publishedAt(PUBLISHED);
    }

    static AdapterRequest request(Post post) {
        return new AdapterRequest("ann", "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa", post, PostContent.of(post),
                Optional.empty());
    }

    /** The text a facet selects, by its UTF-8 byte offsets. */
    static String slice(String text, Facet facet) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int start = facet.index().byteStart().intValue();
        int end = facet.index().byteEnd().intValue();
        return new String(bytes, start, end - start, StandardCharsets.UTF_8);
    }
}
