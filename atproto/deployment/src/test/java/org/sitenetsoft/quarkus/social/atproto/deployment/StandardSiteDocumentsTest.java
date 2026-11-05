package org.sitenetsoft.quarkus.social.atproto.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.sitenetsoft.atproto.syntax.AtUri;
import org.sitenetsoft.atproto.syntax.Cid;
import org.sitenetsoft.quarkus.social.Kind;
import org.sitenetsoft.quarkus.social.Post;
import org.sitenetsoft.quarkus.social.atproto.ContentWarningSetting;
import org.sitenetsoft.quarkus.social.atproto.DefaultStandardSitePublications;
import org.sitenetsoft.quarkus.social.atproto.StandardSiteDocuments;
import org.sitenetsoft.quarkus.social.atproto.StandardSitePublications;
import org.sitenetsoft.quarkus.social.content.PostContent;
import org.sitenetsoft.quarkus.social.spi.AdapterRequest;

import com.atproto.repo.StrongRef;

import site.standard.Document;

class StandardSiteDocumentsTest {

    static final Instant PUBLISHED = Instant.parse("2026-11-04T10:15:30Z");
    static final ContentWarningSetting SKIP = ContentWarningSetting.parse("skip");
    static final StandardSitePublications DEFAULT = new DefaultStandardSitePublications();

    @Test
    void defaultSiteIsUrlOrigin() {
        Post post = article("https://blog.example/2026/hello?x=1").build();
        assertEquals(Optional.of(URI.create("https://blog.example")), DEFAULT.site("ann", post));
        assertEquals(Optional.of(URI.create("https://blog.example:8443")),
                DEFAULT.site("ann", article("https://blog.example:8443/a").build()));

        Document doc = StandardSiteDocuments.build(request(post), SKIP,
                StandardSiteDocuments.site(DEFAULT, request(post)), Optional.empty(), Optional.empty());
        assertEquals(URI.create("https://blog.example"), doc.site());
        assertEquals("/2026/hello", doc.path());
        assertEquals("Hello world", doc.title());
        assertEquals("One line", doc.description());
        assertEquals("First paragraph.\n\nSecond paragraph.", doc.textContent());
        assertEquals(List.of("quarkus", "java"), doc.tags());
        assertEquals(PUBLISHED, doc.publishedAt().instant());
        assertNull(doc.updatedAt());
        assertNull(doc.bskyPostRef());
        assertNull(doc.labels());
    }

    @Test
    void appPublicationWins() {
        URI publication = URI.create("at://did:plc:aaaaaaaaaaaaaaaaaaaaaaaa/site.standard.publication/self");
        StandardSitePublications app = (account, post) -> Optional.of(publication);
        AdapterRequest req = request(article("https://blog.example/hello").build());
        assertEquals(publication, StandardSiteDocuments.site(app, req));
        // an app that has no publication for this post: the default
        assertEquals(URI.create("https://blog.example"),
                StandardSiteDocuments.site((account, post) -> Optional.empty(), req));
        Document doc = StandardSiteDocuments.build(req, SKIP, StandardSiteDocuments.site(app, req),
                Optional.empty(), Optional.of(PUBLISHED.plusSeconds(60)));
        assertEquals(publication, doc.site());
        assertEquals("/hello", doc.path());
        assertEquals(PUBLISHED.plusSeconds(60), doc.updatedAt().instant());
    }

    @Test
    void bskyPostRefSet() {
        StrongRef ref = new StrongRef(
                AtUri.parse("at://did:plc:aaaaaaaaaaaaaaaaaaaaaaaa/app.bsky.feed.post/3m4abcdefgh22"),
                Cid.parse("bafyreie5737gdxlw5i64vzichcalba3z2v5n6icifvx5xytvske7mr3hpm"));
        AdapterRequest req = request(article("https://blog.example/hello").build());
        Document doc = StandardSiteDocuments.build(req, SKIP, URI.create("https://blog.example"),
                Optional.of(ref), Optional.empty());
        assertEquals(ref, doc.bskyPostRef());
    }

    static Post.Builder article(String url) {
        return Post.builder("doc-1").kind(Kind.ARTICLE).title("Hello world").summary("One line")
                .html("<p>First paragraph.</p><p>Second paragraph.</p>").url(URI.create(url))
                .tags(List.of("quarkus", "java")).publishedAt(PUBLISHED);
    }

    static AdapterRequest request(Post post) {
        return new AdapterRequest("ann", "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa", post, PostContent.of(post),
                Optional.empty());
    }
}
