package org.sitenetsoft.quarkus.social.atproto.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sitenetsoft.quarkus.social.Network.ATPROTO;
import static org.sitenetsoft.quarkus.social.test.SocialAssertions.assertSkipped;

import java.util.logging.Level;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.social.NetworkResult;
import org.sitenetsoft.quarkus.social.Post;
import org.sitenetsoft.quarkus.social.SkipReason;
import org.sitenetsoft.quarkus.social.Social;

import io.quarkus.test.QuarkusExtensionTest;

/** {@code quarkus.atproto.oauth.mode=login} (the default) has no app accounts: the adapter can't publish. */
public class LoginModeTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(AtprotoApps.class, AtprotoApps.NoDns.class))
            .setLogRecordPredicate(r -> r.getLoggerName().startsWith("org.sitenetsoft.quarkus.social.atproto")
                    && r.getLevel().intValue() >= Level.WARNING.intValue())
            .assertLogRecords(records -> {
                assertEquals(1, records.size(), () -> records.stream().map(r -> r.getMessage()).toList().toString());
                assertEquals(Level.SEVERE, records.get(0).getLevel());
                assertTrue(records.get(0).getMessage().contains("quarkus.atproto.oauth.mode must be link"),
                        records.get(0).getMessage());
            });

    @Inject
    Social social;

    @Test
    void loginModeSkipsWithReason() {
        NetworkResult.Skipped skipped = assertSkipped(social.publish("ann", Post.builder("login-1").text("Hello")
                .build()), ATPROTO, SkipReason.NOT_LINKED);
        assertEquals("quarkus.atproto.oauth.mode must be link", skipped.detail());
    }
}
