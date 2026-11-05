package org.sitenetsoft.quarkus.social.atproto.deployment;

import org.sitenetsoft.quarkus.social.atproto.AtprotoSocialConfig;

/** A hand-made {@link AtprotoSocialConfig} for the unit tests (the defaults, changed with the withers). */
record TestConfig(Output output, String contentWarning, EditPost editPost) implements AtprotoSocialConfig {

    static TestConfig defaults() {
        return new TestConfig(Output.POST_AND_DOCUMENT, "skip", EditPost.KEEP);
    }

    TestConfig withOutput(Output o) {
        return new TestConfig(o, contentWarning, editPost);
    }

    TestConfig withContentWarning(String cw) {
        return new TestConfig(output, cw, editPost);
    }

    TestConfig withEditPost(EditPost e) {
        return new TestConfig(output, contentWarning, e);
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public String postUrlTemplate() {
        return "https://bsky.app/profile/{did}/post/{rkey}";
    }
}
