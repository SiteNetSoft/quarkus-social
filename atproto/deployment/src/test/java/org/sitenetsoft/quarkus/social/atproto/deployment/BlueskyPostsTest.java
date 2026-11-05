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
import org.sitenetsoft.quarkus.social.atproto.ContentWarningSetting;
import org.sitenetsoft.quarkus.social.content.PostContent;
import org.sitenetsoft.quarkus.social.spi.AdapterRequest;

import app.bsky.feed.Post.Embed;
import app.bsky.feed.Post.Labels;
import app.bsky.richtext.Facet;

class BlueskyPostsTest {

    static final Instant PUBLISHED = Instant.parse("2026-11-04T10:15:30.123456Z");
    static final URI URL = URI.create("https://blog.example/posts/hello");
    static final ContentWarningSetting SKIP = ContentWarningSetting.parse("skip");

    @Test
    void noteTextWithFacets() {
        String text = "Café 👩‍💻 https://blog.example/é #quarkus";
        app.bsky.feed.Post post = BlueskyPosts.build(request(Post.builder("n1").text(text).language("fr")
                .publishedAt(PUBLISHED).build()), SKIP);
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
                () -> BlueskyPosts.build(request(Post.builder("n2").text(text).build()), SKIP));
        NetworkResult.Failed failed = assertInstanceOf(NetworkResult.Failed.class, refused.result());
        assertEquals(FailureCode.TEXT_TOO_LONG, failed.code());
        assertFalse(failed.retryable());
        // exactly 300 is fine
        assertEquals(300, Graphemes.count(BlueskyPosts.build(request(Post.builder("n3").text("é".repeat(300))
                .build()), SKIP).text()));
    }

    @Test
    void articleTeaserTrimmedWithCard() {
        String summary = "word ".repeat(80); // 400 graphemes
        app.bsky.feed.Post post = BlueskyPosts.build(request(article("a1").summary(summary).build()),
                SKIP);
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
                .html("<p>First paragraph #java.</p><p>Second paragraph.</p>").build()), SKIP);
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
                SKIP);
        assertTrue(Graphemes.count(post.text()) <= 300);
        assertTrue(post.text().endsWith("👩‍💻…"), post.text());
        String body = post.text().substring(0, post.text().length() - 1);
        assertEquals(0, body.replace("👩‍💻", "").replace("x", "").replace("Hello world\n\n", "").length(),
                "no broken emoji pieces: " + body);
    }

    @Test
    void teaserDropsFacetsTheCutReaches() {
        // the summary ends in a URL that the 300-grapheme cut goes through, and a tag before it survives
        String summary = "#kept " + "y".repeat(270) + " https://blog.example/a-long-path-that-gets-cut";
        app.bsky.feed.Post post = BlueskyPosts.build(request(article("a4").summary(summary).build()), SKIP);
        assertTrue(post.text().endsWith("…"), post.text());
        assertEquals(1, post.facets().size(), () -> post.facets().toString());
        assertEquals("#kept", slice(post.text(), post.facets().get(0)));
        // a tag ending right at the cut is dropped too: it may have been longer
        String tagged = "y".repeat(280) + " #abcdefghijklmnopqrstuvwxyz";
        app.bsky.feed.Post cutTag = BlueskyPosts.build(request(article("a5").summary(tagged).build()), SKIP);
        assertNull(cutTag.facets(), () -> cutTag.facets().toString());
        // nothing cut: every facet stays
        app.bsky.feed.Post whole = BlueskyPosts.build(request(article("a6").summary("See https://blog.example/x #tag")
                .build()), SKIP);
        assertEquals(2, whole.facets().size());
    }

    @Test
    void labelSettingAddsSelfLabel() {
        Post sensitive = Post.builder("n4").text("Careful").contentWarning("gore").build();
        app.bsky.feed.Post labelled = BlueskyPosts.build(request(sensitive),
                ContentWarningSetting.parse("label:graphic-media"));
        Labels.SelfLabels labels = assertInstanceOf(Labels.SelfLabels.class, labelled.labels());
        assertEquals(List.of("graphic-media"), labels.value().values().stream().map(l -> l.val()).toList());
        // drop: no label
        assertNull(BlueskyPosts.build(request(sensitive), ContentWarningSetting.parse("drop")).labels());
        // the post's own hint wins over the setting, and applies without a warning too
        Post hinted = Post.builder("n5").text("Art").atprotoLabels(List.of("nudity")).build();
        Labels.SelfLabels own = assertInstanceOf(Labels.SelfLabels.class, BlueskyPosts.build(request(hinted),
                ContentWarningSetting.parse("label:porn")).labels());
        assertEquals(List.of("nudity"), own.value().values().stream().map(l -> l.val()).toList());
    }

    @Test
    void tagsFromThePostWithinTheLexiconLimits() {
        assertNull(BlueskyPosts.build(request(Post.builder("t0").text("x").build()), SKIP).tags());
        List<String> ten = java.util.stream.IntStream.range(0, 10).mapToObj(i -> "tag" + i).toList();
        String tooLong = "é".repeat(65); // over 64 graphemes: left out, not cut
        List<String> tags = new java.util.ArrayList<>(List.of("quarkus", tooLong, "été"));
        tags.addAll(ten);
        app.bsky.feed.Post post = BlueskyPosts.build(request(Post.builder("t1").text("x").tags(tags).build()), SKIP);
        assertEquals(List.of("quarkus", "été", "tag0", "tag1", "tag2", "tag3", "tag4", "tag5"), post.tags());
        // exactly 64 graphemes fits; 64 graphemes over 640 UTF-8 bytes (11 bytes each) does not
        String edge = "é".repeat(64);
        String heavy = "👩‍💻".repeat(64);
        assertEquals(List.of(edge), BlueskyPosts.build(request(Post.builder("t2").text("x")
                .tags(List.of(heavy, edge)).build()), SKIP).tags());
    }

    @Test
    void htmlOnlyNoteKeepsItsLinks() {
        app.bsky.feed.Post post = BlueskyPosts.build(request(Post.builder("h1")
                .html("<p>Read <a href=\"https://blog.example/posts/hello\">my post</a>.</p>").build()), SKIP);
        assertEquals("Read my post (https://blog.example/posts/hello).", post.text());
        assertEquals(1, post.facets().size());
        assertEquals("https://blog.example/posts/hello", slice(post.text(), post.facets().get(0)));
        assertInstanceOf(Facet.Features.Link.class, post.facets().get(0).features().get(0));
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
