package org.sitenetsoft.quarkus.social.atproto;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "quarkus.social.atproto")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface AtprotoSocialConfig {

    /** Whether posts go to Bluesky. When false every call is answered {@code Skipped(DISABLED_BY_CONFIG)}. */
    @WithDefault("true")
    boolean enabled();

    /** Which records a post becomes. */
    @WithDefault("post-and-document")
    Output output();

    /**
     * What a post with a content warning (or marked sensitive) becomes, as Bluesky has only a fixed set of
     * self-labels: {@code skip} (not published there), {@code drop} (published without a warning) or
     * {@code label:<sexual|nudity|porn|graphic-media>} (published with that self-label). Anything else fails at
     * startup. A post's own {@code atprotoLabels} win over this setting.
     */
    @WithDefault("skip")
    String contentWarning();

    /**
     * What an edit does to the Bluesky post: {@code keep} it as it is (Bluesky shows no edits; the Standard.site
     * document is still updated) or {@code recreate} it (delete and create again, losing its likes and replies).
     */
    @WithDefault("keep")
    EditPost editPost();

    /** The human-readable link of a post, from its DID and record key. */
    @WithDefault("https://bsky.app/profile/{did}/post/{rkey}")
    String postUrlTemplate();

    enum Output {
        /** An {@code app.bsky.feed.post}, and for an {@code ARTICLE} also a {@code site.standard.document}. */
        POST_AND_DOCUMENT,
        /** Only the {@code app.bsky.feed.post}. */
        POST,
        /** Only the {@code site.standard.document} ({@code ARTICLE} posts only). */
        DOCUMENT
    }

    enum EditPost {
        KEEP,
        RECREATE
    }
}
