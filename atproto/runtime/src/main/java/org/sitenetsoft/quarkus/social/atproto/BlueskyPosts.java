package org.sitenetsoft.quarkus.social.atproto;

import java.text.BreakIterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.sitenetsoft.atproto.syntax.Datetime;
import org.sitenetsoft.atproto.syntax.Language;
import org.sitenetsoft.quarkus.atproto.lexicon.Graphemes;
import org.sitenetsoft.quarkus.atproto.lexicons.bsky.RichText;
import org.sitenetsoft.quarkus.social.FailureCode;
import org.sitenetsoft.quarkus.social.Kind;
import org.sitenetsoft.quarkus.social.Network;
import org.sitenetsoft.quarkus.social.NetworkResult;
import org.sitenetsoft.quarkus.social.Post;
import org.sitenetsoft.quarkus.social.spi.AdapterRequest;

import com.atproto.label.Defs;

import app.bsky.embed.External;
import app.bsky.richtext.Facet;

/**
 * The {@code app.bsky.feed.post} of a post.
 * <ul>
 * <li>NOTE: the post's plain text, refused ({@code TEXT_TOO_LONG}) over {@value #MAX_GRAPHEMES} graphemes, never
 * truncated;</li>
 * <li>ARTICLE: a teaser (title, a blank line, the summary or the first paragraph) trimmed with "…" to fit, and an
 * {@code app.bsky.embed.external} link card to the canonical URL.</li>
 * </ul>
 * Link and hashtag facets, {@code tags} from the post's tags (see {@link #tags}), {@code langs} from the language,
 * {@code createdAt} from {@code publishedAt}, self-labels from the post's {@code atprotoLabels} or the
 * {@code content-warning} setting.
 */
public final class BlueskyPosts {

    static final int MAX_GRAPHEMES = 300;
    /** The Lexicon's limits on {@code tags}: at most 8, each at most 64 graphemes and 640 UTF-8 bytes. */
    static final int MAX_TAGS = 8;
    private static final int MAX_TAG_GRAPHEMES = 64;
    private static final int MAX_TAG_BYTES = 640;
    private static final String ELLIPSIS = "…";
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n[ \\t]*\\n\\s*");

    private BlueskyPosts() {
    }

    /** A post that can't go to Bluesky; {@link #result()} is what the adapter answers. */
    public static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient NetworkResult result;

        Refused(NetworkResult result) {
            super(result.toString(), null, false, false);
            this.result = result;
        }

        public NetworkResult result() {
            return result;
        }
    }

    /** @throws Refused ({@code TEXT_TOO_LONG}) for a NOTE over 300 graphemes */
    public static app.bsky.feed.Post build(AdapterRequest req, ContentWarningSetting cw) {
        Post post = req.post();
        String text;
        List<Facet> facets;
        app.bsky.feed.Post.Embed embed = null;
        if (post.kind() == Kind.ARTICLE) {
            String title = post.title().orElseThrow();
            Optional<String> summary = post.summary().filter(s -> !s.isBlank());
            String lead = summary.or(() -> firstParagraph(req.content().text())).orElse("");
            String full = lead.isEmpty() ? title : title + "\n\n" + lead;
            text = RichText.truncate(full, MAX_GRAPHEMES, ELLIPSIS);
            facets = text.equals(full) ? RichText.of(text).facets() : facetsBeforeCut(text);
            External.ExternalDef card = new External.ExternalDef(post.url().orElseThrow(), title,
                    summary.orElse(""), null, null);
            embed = new app.bsky.feed.Post.Embed.External(new External(card));
        } else {
            text = req.content().text();
            int graphemes = Graphemes.count(text);
            if (graphemes > MAX_GRAPHEMES) {
                throw new Refused(new NetworkResult.Failed(Network.ATPROTO, FailureCode.TEXT_TOO_LONG,
                        "the text has " + graphemes + " graphemes; a Bluesky post takes at most " + MAX_GRAPHEMES,
                        false, Map.of()));
            }
            facets = RichText.of(text).facets();
        }
        List<String> labels = selfLabels(post, cw);
        return new app.bsky.feed.Post(text, null, facets.isEmpty() ? null : facets, null, embed,
                post.language().filter(Language::isValid).map(l -> List.of(Language.parse(l))).orElse(null),
                labels.isEmpty() ? null : new app.bsky.feed.Post.Labels.SelfLabels(selfLabels(labels)), tags(post),
                Datetime.of(post.publishedAt()));
    }

    /**
     * The record's {@code tags}: the post's first {@value #MAX_TAGS} tags that fit the Lexicon's per-tag limits (a
     * longer tag is left out, never cut), or null when none.
     */
    static List<String> tags(Post post) {
        List<String> tags = post.tags().stream()
                .filter(t -> Graphemes.count(t) <= MAX_TAG_GRAPHEMES && Graphemes.utf8Length(t) <= MAX_TAG_BYTES)
                .limit(MAX_TAGS).toList();
        return tags.isEmpty() ? null : tags;
    }

    /**
     * The facets of a trimmed teaser ({@code text} ends with the ellipsis): a link or tag reaching into the last
     * grapheme before the ellipsis may have been cut (a partial URL or tag), so it is dropped.
     */
    private static List<Facet> facetsBeforeCut(String text) {
        String kept = text.substring(0, text.length() - ELLIPSIS.length());
        BreakIterator graphemes = BreakIterator.getCharacterInstance();
        graphemes.setText(kept);
        int lastStart = graphemes.preceding(kept.length());
        long limit = Graphemes.utf8Length(kept.substring(0, Math.max(lastStart, 0)));
        return RichText.of(text).facets().stream().filter(f -> f.index().byteEnd() <= limit).toList();
    }

    /**
     * The self-label values of a post: its own {@code atprotoLabels} when it has some, else the
     * {@code label:<value>} setting's value when the post has a content warning or is sensitive, else none.
     */
    public static List<String> selfLabels(Post post, ContentWarningSetting setting) {
        if (!post.atprotoLabels().isEmpty()) {
            return post.atprotoLabels();
        }
        if (warned(post) && setting.mode() == ContentWarningSetting.Mode.LABEL) {
            return List.of(setting.label().orElseThrow());
        }
        return List.of();
    }

    static Defs.SelfLabels selfLabels(List<String> values) {
        return new Defs.SelfLabels(values.stream().map(Defs.SelfLabel::new).toList());
    }

    /** Whether the post carries a content warning or is marked sensitive. */
    public static boolean warned(Post post) {
        return post.sensitive() || post.contentWarning().filter(w -> !w.isBlank()).isPresent();
    }

    private static Optional<String> firstParagraph(String text) {
        for (String paragraph : PARAGRAPH_BREAK.split(text.strip())) {
            if (!paragraph.isBlank()) {
                return Optional.of(paragraph.strip());
            }
        }
        return Optional.empty();
    }

}
