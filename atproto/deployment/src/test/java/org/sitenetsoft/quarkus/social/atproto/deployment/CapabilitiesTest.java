package org.sitenetsoft.quarkus.social.atproto.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;

import org.junit.jupiter.api.Test;
import org.sitenetsoft.quarkus.social.Capabilities;
import org.sitenetsoft.quarkus.social.Kind;
import org.sitenetsoft.quarkus.social.atproto.AtprotoSocialAdapter;
import org.sitenetsoft.quarkus.social.atproto.AtprotoSocialConfig.EditPost;
import org.sitenetsoft.quarkus.social.atproto.AtprotoSocialConfig.Output;

/** The adapter's capabilities follow its settings. */
class CapabilitiesTest {

    @Test
    void editsUnlessOnlyKeptPostsAreWritten() {
        TestConfig defaults = TestConfig.defaults();
        assertTrue(AtprotoSocialAdapter.capabilities(defaults).edits(), "the document is edited");
        assertFalse(AtprotoSocialAdapter.capabilities(defaults.withOutput(Output.POST)).edits(),
                "output=post, edit-post=keep: nothing is edited");
        assertTrue(AtprotoSocialAdapter.capabilities(defaults.withOutput(Output.POST)
                .withEditPost(EditPost.RECREATE)).edits());
        Capabilities documentOnly = AtprotoSocialAdapter.capabilities(defaults.withOutput(Output.DOCUMENT));
        assertTrue(documentOnly.edits());
        assertEquals(EnumSet.of(Kind.ARTICLE), documentOnly.kinds());
    }

    @Test
    void contentWarningsWhenTheyBecomeALabel() {
        TestConfig defaults = TestConfig.defaults();
        assertFalse(AtprotoSocialAdapter.capabilities(defaults).contentWarnings(), "skip");
        assertFalse(AtprotoSocialAdapter.capabilities(defaults.withContentWarning("drop")).contentWarnings());
        assertTrue(AtprotoSocialAdapter.capabilities(defaults.withContentWarning("label:graphic-media"))
                .contentWarnings());
    }
}
