package org.sitenetsoft.quarkus.social.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.logging.Level;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.social.Post;
import org.sitenetsoft.quarkus.social.Social;
import org.sitenetsoft.quarkus.social.SocialException;

import io.quarkus.test.QuarkusExtensionTest;

/** An app with the core but no adapter yet starts (with a WARN) and refuses to publish. */
public class NoAdapterTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .setLogRecordPredicate(r -> r.getLevel().intValue() >= Level.WARNING.intValue()
                    && r.getLoggerName().startsWith("org.sitenetsoft.quarkus.social"))
            .assertLogRecords(records -> assertEquals(1,
                    records.stream().filter(r -> r.getMessage().contains("no SocialAdapter")).count(),
                    records.toString()));

    @Inject
    Social social;

    @Test
    void noAdapterThrows() {
        SocialException e = assertThrows(SocialException.class,
                () -> social.publish("alice", Post.builder("p1").text("Hello").build()));
        assertTrue(e.getMessage().contains("no active SocialAdapter"), e.getMessage());
        assertTrue(social.capabilities().isEmpty());
    }
}
