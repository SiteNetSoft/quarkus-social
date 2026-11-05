package org.sitenetsoft.quarkus.social.atproto.deployment;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

/** An unknown {@code quarkus.social.atproto.content-warning} value fails at startup. */
public class InvalidContentWarningTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(AtprotoApps.class, AtprotoApps.NoDns.class))
            .overrideConfigKey("quarkus.atproto.oauth.mode", "link")
            .overrideConfigKey("quarkus.social.atproto.content-warning", "label:spoiler")
            .assertException(e -> {
                Throwable t = e;
                while (t != null && (t.getMessage() == null
                        || !t.getMessage().contains("quarkus.social.atproto.content-warning"))) {
                    t = t.getCause();
                }
                assertTrue(t != null, () -> "unexpected startup failure: " + e);
            });

    @Test
    void startupFails() {
        fail("the application must not start");
    }
}
