package org.sitenetsoft.quarkus.social.activitypub;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "quarkus.social.activitypub")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface ActivityPubSocialConfig {

    /**
     * Whether posts go to the Fediverse. When false every call is answered {@code Skipped(DISABLED_BY_CONFIG)}.
     */
    @WithDefault("true")
    boolean enabled();

    /** What an {@code ARTICLE} post becomes. */
    @WithDefault("note-with-link")
    ArticleStyle articleStyle();

    enum ArticleStyle {
        /**
         * A {@code Note} with the title, the summary (or the first paragraph) and the canonical link, {@code url} set
         * to the canonical URL: Mastodon shows it as a normal post with a link preview.
         */
        NOTE_WITH_LINK,
        /** An {@code Article} with {@code name}, {@code summary}, the full HTML {@code content} and {@code url}. */
        ARTICLE
    }
}
