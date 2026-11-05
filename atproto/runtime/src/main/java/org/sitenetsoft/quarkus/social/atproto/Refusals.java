package org.sitenetsoft.quarkus.social.atproto;

import java.util.Map;
import java.util.Optional;

import org.sitenetsoft.quarkus.social.FailureCode;
import org.sitenetsoft.quarkus.social.Kind;
import org.sitenetsoft.quarkus.social.Network;
import org.sitenetsoft.quarkus.social.NetworkResult;
import org.sitenetsoft.quarkus.social.Post;
import org.sitenetsoft.quarkus.social.SkipReason;
import org.sitenetsoft.quarkus.social.Visibility;
import org.sitenetsoft.quarkus.social.atproto.AtprotoSocialConfig.EditPost;
import org.sitenetsoft.quarkus.social.atproto.AtprotoSocialConfig.Output;
import org.sitenetsoft.quarkus.social.spi.AdapterRequest;

/** What Bluesky can't carry, decided before anything is written. */
public final class Refusals {

    private static final Network NETWORK = Network.ATPROTO;

    private Refusals() {
    }

    /**
     * Publish: a non-public post, a NOTE for a document-only output, a content warning with
     * {@code content-warning=skip} (unless the post names its own self-labels) are skipped.
     */
    public static Optional<NetworkResult> publish(AdapterRequest req, Output output, ContentWarningSetting cw) {
        Post post = req.post();
        if (post.visibility() != Visibility.PUBLIC) {
            return Optional.of(new NetworkResult.Skipped(NETWORK, SkipReason.VISIBILITY_NOT_SUPPORTED,
                    "Bluesky posts are public; this one is " + post.visibility()));
        }
        if (post.kind() == Kind.NOTE && output == Output.DOCUMENT) {
            return Optional.of(new NetworkResult.Skipped(NETWORK, SkipReason.KIND_NOT_SUPPORTED,
                    "quarkus.social.atproto.output=document publishes only ARTICLE posts"));
        }
        if (skipped(post, cw)) {
            return Optional.of(new NetworkResult.Skipped(NETWORK, SkipReason.CONTENT_WARNING_NOT_SUPPORTED,
                    "Bluesky has no free-text content warning (quarkus.social.atproto.content-warning=skip)"));
        }
        return Optional.empty();
    }

    /**
     * Update: the post is already on Bluesky. A content warning that is new in this edit can't reach a Bluesky post
     * that is kept ({@code edit-post=keep}: no relabelling without recreating it), whatever the content-warning
     * setting; with {@code content-warning=skip} a warned post can't be carried at all. Both leave the network
     * unchanged, so the app can delete the post there.
     */
    public static Optional<NetworkResult> update(AdapterRequest req, EditPost editPost, ContentWarningSetting cw) {
        Post post = req.post();
        boolean newWarning = BlueskyPosts.warned(post) && !req.stored().map(BlueskyPosts::warned).orElse(false);
        boolean postKept = editPost == EditPost.KEEP && AtprotoRefs.previous(req, AtprotoRefs.POST).isPresent();
        if (newWarning && postKept) {
            return Optional.of(new NetworkResult.Failed(NETWORK, FailureCode.CONTENT_WARNING_NOT_SUPPORTED,
                    "the edit adds a content warning, which a kept Bluesky post can't take "
                            + "(quarkus.social.atproto.edit-post=keep); the existing Bluesky post is unchanged",
                    false, Map.of()));
        }
        if (skipped(post, cw)) {
            return Optional.of(new NetworkResult.Failed(NETWORK, FailureCode.CONTENT_WARNING_NOT_SUPPORTED,
                    "the post now has a content warning, which Bluesky can't show "
                            + "(quarkus.social.atproto.content-warning=skip); the existing Bluesky copy is unchanged",
                    false, Map.of()));
        }
        return Optional.empty();
    }

    private static boolean skipped(Post post, ContentWarningSetting cw) {
        return BlueskyPosts.warned(post) && post.atprotoLabels().isEmpty()
                && cw.mode() == ContentWarningSetting.Mode.SKIP;
    }
}
