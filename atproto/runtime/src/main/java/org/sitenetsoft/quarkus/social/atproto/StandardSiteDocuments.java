package org.sitenetsoft.quarkus.social.atproto;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.sitenetsoft.atproto.syntax.Datetime;
import org.sitenetsoft.quarkus.social.Post;
import org.sitenetsoft.quarkus.social.spi.AdapterRequest;

import com.atproto.repo.StrongRef;

import site.standard.Document;

/**
 * The {@code site.standard.document} of an ARTICLE: {@code site} (see {@link StandardSitePublications}), {@code path}
 * (the canonical URL's path), {@code title}, {@code description} (the summary), {@code textContent} (plain text),
 * {@code tags}, {@code publishedAt}, {@code updatedAt} (edits), {@code bskyPostRef} (the Bluesky post, when there is
 * one) and the self-labels the Bluesky post gets.
 */
public final class StandardSiteDocuments {

    private StandardSiteDocuments() {
    }

    public static Document build(AdapterRequest req, AtprotoSocialConfig cfg, URI site,
            Optional<StrongRef> bskyPostRef, Optional<Instant> updatedAt) {
        Post post = req.post();
        String path = post.url().map(URI::getRawPath).filter(p -> !p.isEmpty()).orElse(null);
        String text = req.content().text();
        List<String> labels = BlueskyPosts.selfLabels(post, ContentWarningSetting.parse(cfg.contentWarning()));
        return new Document(path, site, post.tags().isEmpty() ? null : post.tags(), null, post.title().orElseThrow(),
                labels.isEmpty() ? null : new Document.Labels.SelfLabels(BlueskyPosts.selfLabels(labels)), null,
                updatedAt.map(Datetime::of).orElse(null), null, bskyPostRef.orElse(null),
                post.summary().filter(s -> !s.isBlank()).orElse(null), Datetime.of(post.publishedAt()),
                text.isBlank() ? null : text, null);
    }

    /** The app's answer, or the default (the canonical URL's origin) when it has none. */
    public static URI site(StandardSitePublications publications, AdapterRequest req) {
        return publications.site(req.account(), req.post())
                .or(() -> req.post().url().map(DefaultStandardSitePublications::origin))
                .orElseThrow(() -> new IllegalArgumentException("post " + req.post().id() + " has no url"));
    }
}
