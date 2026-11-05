package org.sitenetsoft.quarkus.social.activitypub.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.sitenetsoft.activitystreams.ASObject;
import org.sitenetsoft.activitystreams.Article;
import org.sitenetsoft.activitystreams.Audience;
import org.sitenetsoft.activitystreams.Hashtag;
import org.sitenetsoft.activitystreams.Note;
import org.sitenetsoft.activitystreams.ObjectOrLink;
import org.sitenetsoft.quarkus.activitypub.ActivityPubUrls;
import org.sitenetsoft.quarkus.activitypub.actor.LocalActor;
import org.sitenetsoft.quarkus.social.Kind;
import org.sitenetsoft.quarkus.social.Post;
import org.sitenetsoft.quarkus.social.Visibility;
import org.sitenetsoft.quarkus.social.activitypub.ActivityPubSocialConfig.ArticleStyle;
import org.sitenetsoft.quarkus.social.activitypub.PostMapper;
import org.sitenetsoft.quarkus.social.content.PostContent;
import org.sitenetsoft.quarkus.social.spi.AdapterRequest;

/** The ActivityPub object a post becomes (no Quarkus app: {@link ActivityPubUrls} is stubbed). */
public class PostMapperTest {

    static final String BASE = "https://ap-test.example.net";
    static final URI FOLLOWERS = URI.create(BASE + "/users/alice/followers");
    static final URI ARTICLE_URL = URI.create("https://blog.example/hello");

    static final ActivityPubUrls URLS = new ActivityPubUrls() {
        @Override
        public URI baseUrl() {
            return URI.create(BASE);
        }

        @Override
        public URI actor(String username) {
            return URI.create(BASE + "/users/" + username);
        }

        @Override
        public URI followers(String username) {
            return URI.create(BASE + "/users/" + username + "/followers");
        }

        @Override
        public URI object(String id) {
            return URI.create(BASE + "/objects/" + id);
        }

        @Override
        public URI activity(String id) {
            return URI.create(BASE + "/activities/" + id);
        }
    };

    static final LocalActor ALICE = LocalActor.builder("alice").id(URI.create(BASE + "/users/alice")).build();

    final PostMapper notes = new PostMapper(ArticleStyle.NOTE_WITH_LINK);
    final PostMapper articles = new PostMapper(ArticleStyle.ARTICLE);

    static ASObject map(PostMapper mapper, Post post) {
        return mapper.toObject(ALICE, new AdapterRequest("alice", "alice", post, PostContent.of(post), Optional.empty()),
                URLS);
    }

    static Post.Builder note() {
        return Post.builder("post-1").text("Hello fediverse");
    }

    static Post.Builder article() {
        return Post.builder("post-2").kind(Kind.ARTICLE).title("Hello & bye").url(ARTICLE_URL)
                .html("<p>First <em>para</em>.</p><p>Second.</p>");
    }

    static List<URI> uris(List<ObjectOrLink> values) {
        return values.stream().map(v -> v.uri().orElseThrow()).toList();
    }

    @Test
    void publicGoesToPublicCcFollowers() {
        ASObject o = map(notes, note().visibility(Visibility.PUBLIC).build());
        assertEquals(List.of(Audience.PUBLIC), uris(o.to()));
        assertEquals(List.of(FOLLOWERS), uris(o.cc()));
    }

    @Test
    void unlistedGoesToFollowersCcPublic() {
        ASObject o = map(notes, note().visibility(Visibility.UNLISTED).build());
        assertEquals(List.of(FOLLOWERS), uris(o.to()));
        assertEquals(List.of(Audience.PUBLIC), uris(o.cc()));
    }

    @Test
    void followersGoesToFollowersOnly() {
        ASObject o = map(notes, note().visibility(Visibility.FOLLOWERS).build());
        assertEquals(List.of(FOLLOWERS), uris(o.to()));
        assertEquals(List.of(), o.cc());
        assertFalse(o.isPublic());
    }

    @Test
    void noteCarriesTheHtmlAndIsAttributedToTheActor() {
        ASObject o = map(notes, note().build());
        assertInstanceOf(Note.class, o);
        assertEquals("<p>Hello fediverse</p>", o.content());
        assertEquals(List.of(ALICE.id()), uris(o.attributedTo()));
        assertTrue(o.published() != null);
    }

    @Test
    void idIsDeterministicAndUrlSafe() {
        URI first = map(notes, note().build()).id();
        assertEquals(first, map(notes, note().text("other text").build()).id(), "same post id, same object id");
        assertEquals(first, PostMapper.objectId(URLS, "post-1"));
        assertNotEquals(first, PostMapper.objectId(URLS, "post-1 "), "another post id, another object id");
        String key = first.toString().substring((BASE + "/objects/").length());
        assertTrue(key.matches("[0-9a-f]{32}"), key);
        URI odd = PostMapper.objectId(URLS, "a/b?c#d é");
        assertTrue(odd.toString().substring((BASE + "/objects/").length()).matches("[0-9a-f]{32}"), odd.toString());
    }

    @Test
    void tagsBecomeHashtags() {
        ASObject o = map(notes, note().tags(List.of("quarkus", "Java")).build());
        assertEquals(2, o.tag().size());
        Hashtag quarkus = assertInstanceOf(Hashtag.class, o.tag().get(0).object().orElseThrow());
        assertEquals("#quarkus", quarkus.name());
        assertEquals(URI.create(BASE + "/tags/quarkus"), quarkus.href());
        Hashtag java = assertInstanceOf(Hashtag.class, o.tag().get(1).object().orElseThrow());
        assertEquals("#Java", java.name());
        assertEquals("<p>Hello fediverse</p><p><a href=\"" + BASE + "/tags/quarkus\" class=\"mention hashtag\" "
                + "rel=\"tag\">#quarkus</a> <a href=\"" + BASE + "/tags/Java\" class=\"mention hashtag\" "
                + "rel=\"tag\">#Java</a></p>", o.content());
    }

    @Test
    void contentWarningSetsSensitiveAndSummary() {
        ASObject o = map(notes, note().contentWarning("spoilers").build());
        assertEquals(Boolean.TRUE, o.sensitive());
        assertEquals("spoilers", o.summary());

        ASObject plain = map(notes, note().build());
        assertNull(plain.sensitive());
        assertNull(plain.summary());

        ASObject flagged = map(notes, note().sensitive(true).build());
        assertEquals(Boolean.TRUE, flagged.sensitive());
        assertNull(flagged.summary());
    }

    @Test
    void languageGoesToContentMap() {
        ASObject o = map(notes, note().language("fr").build());
        assertEquals(Map.of("fr", "<p>Hello fediverse</p>"), o.contentMap());
        assertEquals("<p>Hello fediverse</p>", o.content());
        assertNull(map(notes, note().build()).contentMap());
    }

    @Test
    void articleAsNoteWithLink() {
        ASObject o = map(notes, article().summary("Short <summary>").build());
        assertInstanceOf(Note.class, o);
        assertEquals("<p><strong>Hello &amp; bye</strong></p><p>Short &lt;summary&gt;</p>"
                + "<p><a href=\"https://blog.example/hello\">https://blog.example/hello</a></p>", o.content());
        assertEquals(List.of(ARTICLE_URL), uris(o.url()));
        assertNull(o.name());

        ASObject noSummary = map(notes, article().build());
        assertEquals("<p><strong>Hello &amp; bye</strong></p><p>First <em>para</em>.</p>"
                + "<p><a href=\"https://blog.example/hello\">https://blog.example/hello</a></p>", noSummary.content());
    }

    @Test
    void articleAsArticle() {
        ASObject o = map(articles, article().summary("Short").tags(List.of("blog")).build());
        assertInstanceOf(Article.class, o);
        assertEquals("Hello & bye", o.name());
        assertEquals("Short", o.summary());
        assertEquals("<p>First <em>para</em>.</p><p>Second.</p><p><a href=\"" + BASE + "/tags/blog\" "
                + "class=\"mention hashtag\" rel=\"tag\">#blog</a></p>", o.content());
        assertEquals(List.of(ARTICLE_URL), uris(o.url()));
        assertEquals(List.of(Audience.PUBLIC), uris(o.to()));
    }
}
