package org.sitenetsoft.quarkus.social.atproto.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.sitenetsoft.quarkus.social.atproto.AtprotoSocialConfig.EditPost;
import org.sitenetsoft.quarkus.social.atproto.AtprotoSocialConfig.Output;
import org.sitenetsoft.quarkus.social.atproto.ScopeAdvisor;

class ScopeAdvisorTest {

    static final String POST = "repo:app.bsky.feed.post?action=create&action=delete";
    static final String DOCUMENT = "repo:site.standard.document?action=create&action=update&action=delete";

    @Test
    void postAndDocument() {
        assertEquals(POST + " " + DOCUMENT, ScopeAdvisor.requiredScope(TestConfig.defaults()));
    }

    @Test
    void postOnly() {
        assertEquals(POST, ScopeAdvisor.requiredScope(TestConfig.defaults().withOutput(Output.POST)));
    }

    @Test
    void documentOnly() {
        assertEquals(DOCUMENT, ScopeAdvisor.requiredScope(TestConfig.defaults().withOutput(Output.DOCUMENT)));
    }

    @Test
    void recreateNeedsNothingExtra() {
        assertEquals(POST + " " + DOCUMENT,
                ScopeAdvisor.requiredScope(TestConfig.defaults().withEditPost(EditPost.RECREATE)));
    }

    @Test
    void missingScopeIsNamed() {
        Optional<String> warning = ScopeAdvisor.warning("atproto " + POST, TestConfig.defaults());
        assertTrue(warning.isPresent());
        assertTrue(warning.get().contains(POST + " " + DOCUMENT), warning.get());
        assertTrue(warning.get().contains("quarkus.atproto.oauth.scope"), warning.get());
        assertEquals(Optional.empty(), ScopeAdvisor.warning("atproto " + POST,
                TestConfig.defaults().withOutput(Output.POST)));
        // narrower actions than needed are not enough
        assertTrue(ScopeAdvisor.warning("atproto repo:app.bsky.feed.post?action=create",
                TestConfig.defaults().withOutput(Output.POST)).isPresent());
        // a scope without actions allows all of them
        assertEquals(Optional.empty(), ScopeAdvisor.warning("atproto repo:app.bsky.feed.post repo:site.standard.document",
                TestConfig.defaults()));
    }

    @Test
    void transitionGenericCovers() {
        assertEquals(Optional.empty(), ScopeAdvisor.warning("atproto transition:generic", TestConfig.defaults()));
    }
}
