package org.sitenetsoft.quarkus.social.atproto.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.sitenetsoft.quarkus.social.FailureCode;
import org.sitenetsoft.quarkus.social.Network;
import org.sitenetsoft.quarkus.social.NetworkResult;
import org.sitenetsoft.quarkus.social.Post;
import org.sitenetsoft.quarkus.social.atproto.AtprotoSocialConfig.EditPost;
import org.sitenetsoft.quarkus.social.atproto.ContentWarningSetting;
import org.sitenetsoft.quarkus.social.atproto.Refusals;
import org.sitenetsoft.quarkus.social.content.PostContent;
import org.sitenetsoft.quarkus.social.spi.AdapterRequest;
import org.sitenetsoft.quarkus.social.store.NetworkState;

/** Content warnings on an edit: what a kept or recreated Bluesky post can take. */
class RefusalsTest {

    static final ContentWarningSetting LABEL = ContentWarningSetting.parse("label:graphic-media");
    static final ContentWarningSetting DROP = ContentWarningSetting.parse("drop");
    static final ContentWarningSetting SKIP = ContentWarningSetting.parse("skip");

    static final Post PLAIN = Post.builder("e1").text("Hello").build();
    static final Post WARNED = Post.builder("e1").text("Hello").contentWarning("gore").build();

    @Test
    void newWarningOnKeptPostFailsWhateverTheSetting() {
        for (ContentWarningSetting cw : new ContentWarningSetting[] {LABEL, DROP, SKIP}) {
            NetworkResult.Failed failed = assertInstanceOf(NetworkResult.Failed.class,
                    Refusals.update(edit(PLAIN, WARNED, true), EditPost.KEEP, cw).orElseThrow(), cw::toString);
            assertEquals(FailureCode.CONTENT_WARNING_NOT_SUPPORTED, failed.code());
            assertFalse(failed.retryable());
            assertTrue(failed.reason().contains("the existing Bluesky post is unchanged"), failed.reason());
        }
    }

    @Test
    void warningAlreadyThereIsCarried() {
        assertEquals(Optional.empty(), Refusals.update(edit(WARNED, WARNED, true), EditPost.KEEP, LABEL));
        assertEquals(Optional.empty(), Refusals.update(edit(PLAIN, PLAIN, true), EditPost.KEEP, SKIP));
    }

    @Test
    void recreatedPostFollowsTheSetting() {
        assertEquals(Optional.empty(), Refusals.update(edit(PLAIN, WARNED, true), EditPost.RECREATE, LABEL));
        assertEquals(Optional.empty(), Refusals.update(edit(PLAIN, WARNED, true), EditPost.RECREATE, DROP));
        NetworkResult.Failed failed = assertInstanceOf(NetworkResult.Failed.class,
                Refusals.update(edit(PLAIN, WARNED, true), EditPost.RECREATE, SKIP).orElseThrow());
        assertTrue(failed.reason().contains("the existing Bluesky copy is unchanged"), failed.reason());
    }

    @Test
    void documentOnlyIsRelabelled() {
        // no Bluesky post to keep: the document takes the label
        assertEquals(Optional.empty(), Refusals.update(edit(PLAIN, WARNED, false), EditPost.KEEP, LABEL));
    }

    static AdapterRequest edit(Post stored, Post edited, boolean withPost) {
        Map<String, String> refs = withPost
                ? Map.of("post.uri", "at://did:plc:aaaaaaaaaaaaaaaaaaaaaaaa/app.bsky.feed.post/3m4abcdefgh22",
                        "post.cid", "bafyreie5737gdxlw5i64vzichcalba3z2v5n6icifvx5xytvske7mr3hpm")
                : Map.of();
        NetworkState state = new NetworkState(Network.ATPROTO, NetworkState.Status.PUBLISHED, refs, Optional.empty(),
                Optional.empty(), Instant.now(), Optional.empty());
        return new AdapterRequest("ann", "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa", edited, PostContent.of(edited),
                Optional.of(state), Optional.of(stored));
    }
}
