package org.sitenetsoft.quarkus.social.atproto.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.logging.Level;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.social.atproto.ScopeAdvisor;

import io.quarkus.test.QuarkusExtensionTest;

/** A configured scope that doesn't cover the output: exactly one startup WARN naming the scope to configure. */
public class ScopeWarningTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(AtprotoApps.class, AtprotoApps.NoDns.class))
            .overrideConfigKey("quarkus.atproto.oauth.mode", "link")
            .overrideConfigKey("quarkus.atproto.oauth.scope", "atproto repo:app.bsky.feed.post?action=create")
            .setLogRecordPredicate(r -> r.getLoggerName().equals(ScopeAdvisor.class.getName()))
            .assertLogRecords(records -> {
                assertEquals(1, records.size(), () -> records.stream().map(r -> r.getMessage()).toList().toString());
                assertEquals(Level.WARNING, records.get(0).getLevel());
                String message = records.get(0).getMessage();
                assertTrue(message.contains(ScopeAdvisorTest.POST + " " + ScopeAdvisorTest.DOCUMENT), message);
                assertTrue(message.contains("quarkus.atproto.oauth.scope"), message);
            });

    @Test
    void warnedOnce() {
        // the log assertion above runs after the application stops
    }
}
