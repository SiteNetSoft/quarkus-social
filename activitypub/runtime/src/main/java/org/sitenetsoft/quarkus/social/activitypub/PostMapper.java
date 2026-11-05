package org.sitenetsoft.quarkus.social.activitypub;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
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
import org.sitenetsoft.quarkus.social.activitypub.ActivityPubSocialConfig.ArticleStyle;
import org.sitenetsoft.quarkus.social.spi.AdapterRequest;

/**
 * The ActivityPub object a post becomes.
 * <ul>
 * <li>Audience: {@code PUBLIC} to Public, cc followers; {@code UNLISTED} to followers, cc Public; {@code FOLLOWERS} to
 * followers only.</li>
 * <li>Tags become {@code Hashtag}s ({@code href} {@code <base-url>/tags/<tag>}) and {@code #tag} links appended to
 * the content.</li>
 * <li>A content warning sets {@code sensitive} and {@code summary} (for an {@code Article} too, replacing the post
 * summary there, as Mastodon shows {@code summary} as the warning); the language keys {@code contentMap}.</li>
 * <li>An {@code ARTICLE} is a {@code Note} with title, teaser and link, or an {@code Article}, per
 * {@code article-style}.</li>
 * </ul>
 * The object id is derived from the post id ({@link #objectId}), so a retried publish names the same object.
 */
public final class PostMapper {

    private final ArticleStyle articleStyle;

    public PostMapper(ArticleStyle articleStyle) {
        this.articleStyle = articleStyle;
    }

    public ASObject toObject(LocalActor actor, AdapterRequest req, ActivityPubUrls urls) {
        Post post = req.post();
        URI followers = urls.followers(actor.username());
        String tagLinks = tagLinks(post.tags(), urls);
        boolean asArticle = post.kind() == Kind.ARTICLE && articleStyle == ArticleStyle.ARTICLE;

        ASObject object = asArticle ? new Article() : new Note();
        object.id(objectId(urls, post.id()));
        object.attributedTo(ObjectOrLink.of(actor.id()));
        object.published(post.publishedAt());
        switch (post.visibility()) {
            case PUBLIC -> object.to(ObjectOrLink.of(Audience.PUBLIC)).cc(ObjectOrLink.of(followers));
            case UNLISTED -> object.to(ObjectOrLink.of(followers)).cc(ObjectOrLink.of(Audience.PUBLIC));
            case FOLLOWERS -> object.to(ObjectOrLink.of(followers));
        }

        String content;
        if (post.kind() != Kind.ARTICLE) {
            content = req.content().html() + tagLinks;
        } else if (asArticle) {
            object.name(post.title().orElseThrow());
            post.summary().filter(s -> !s.isBlank()).ifPresent(object::summary);
            content = req.content().html() + tagLinks;
        } else {
            content = teaser(post, req.content().html(), req.content().text()) + tagLinks;
        }
        object.content(content);
        post.language().ifPresent(language -> object.contentMap(Map.of(language, content)));
        post.url().ifPresent(url -> object.url(ObjectOrLink.of(url)));

        if (post.contentWarning().filter(w -> !w.isBlank()).isPresent()) {
            object.sensitive(true).summary(post.contentWarning().get());
        } else if (post.sensitive()) {
            object.sensitive(true);
        }
        if (!post.tags().isEmpty()) {
            object.tag(post.tags().stream().map(tag -> ObjectOrLink.of(hashtag(tag, urls))).toArray(ObjectOrLink[]::new));
        }
        return object;
    }

    /** The object id of a post: a hash of the post id under {@code paths.objects}, URL-safe whatever the post id. */
    public static URI objectId(ActivityPubUrls urls, String postId) {
        return urls.object(key(postId));
    }

    /** The id of the post's {@code Create} activity, derived like {@link #objectId}. */
    public static URI createId(ActivityPubUrls urls, String postId) {
        return urls.activity(key(postId) + "-create");
    }

    /** {@code <p><strong>title</strong></p><p>summary or first paragraph</p><p><a href=url>url</a></p>}. */
    private static String teaser(Post post, String html, String text) {
        String url = escape(post.url().orElseThrow().toString());
        StringBuilder out = new StringBuilder();
        out.append("<p><strong>").append(escape(post.title().orElseThrow())).append("</strong></p>");
        Optional<String> lead = post.summary().filter(s -> !s.isBlank()).map(PostMapper::escape)
                .or(() -> firstParagraph(html, text));
        lead.ifPresent(l -> out.append("<p>").append(l).append("</p>"));
        out.append("<p><a href=\"").append(url).append("\">").append(url).append("</a></p>");
        return out.toString();
    }

    /** The inner HTML of the first non-empty {@code <p>}, else the first line of the text, escaped. */
    private static Optional<String> firstParagraph(String html, String text) {
        Document doc = Jsoup.parseBodyFragment(html);
        doc.outputSettings().prettyPrint(false);
        for (Element p : doc.select("p")) {
            if (!p.text().isBlank()) {
                return Optional.of(p.html());
            }
        }
        return text.lines().map(String::strip).filter(l -> !l.isEmpty()).findFirst().map(PostMapper::escape);
    }

    private static String tagLinks(List<String> tags, ActivityPubUrls urls) {
        if (tags.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("<p>");
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) {
                out.append(' ');
            }
            out.append("<a href=\"").append(escape(tagUrl(tags.get(i), urls).toString()))
                    .append("\" class=\"mention hashtag\" rel=\"tag\">#").append(escape(tags.get(i))).append("</a>");
        }
        return out.append("</p>").toString();
    }

    private static Hashtag hashtag(String tag, ActivityPubUrls urls) {
        Hashtag hashtag = new Hashtag();
        hashtag.href(tagUrl(tag, urls));
        hashtag.name("#" + tag);
        return hashtag;
    }

    private static URI tagUrl(String tag, ActivityPubUrls urls) {
        String base = urls.baseUrl().toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + "/tags/" + URLEncoder.encode(tag, StandardCharsets.UTF_8));
    }

    private static String key(String postId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(postId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
