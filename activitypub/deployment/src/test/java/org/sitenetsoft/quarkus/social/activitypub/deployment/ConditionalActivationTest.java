package org.sitenetsoft.quarkus.social.activitypub.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.sitenetsoft.quarkus.social.Network.ACTIVITYPUB;
import static org.sitenetsoft.quarkus.social.test.SocialAssertions.assertSkipped;

import java.util.List;
import java.util.Set;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.social.Kind;
import org.sitenetsoft.quarkus.social.Post;
import org.sitenetsoft.quarkus.social.SkipReason;
import org.sitenetsoft.quarkus.social.Social;
import org.sitenetsoft.quarkus.social.Visibility;
import org.sitenetsoft.quarkus.social.activitypub.ActivityPubSocialAdapter;
import org.sitenetsoft.quarkus.social.spi.SocialAdapter;

import io.quarkus.arc.All;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * An app with {@code quarkus-social} and {@code quarkus-activitypub} gets the adapter bean.
 * <p>
 * {@code QuarkusExtensionTest} runs on this module's class path, where the adapter is a direct dependency, so the
 * conditional-dependency activation itself (no direct dependency on the adapter) is checked by the
 * {@code integration-tests} module. Also checks {@code quarkus.social.activitypub.enabled=false}.
 */
public class ConditionalActivationTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withEmptyApplication()
            .overrideConfigKey("quarkus.activitypub.base-url", "https://ap-test.example.net")
            .overrideConfigKey("quarkus.social.activitypub.enabled", "false");

    @Inject
    @All
    List<SocialAdapter> adapters;
    @Inject
    Social social;

    @Test
    void adapterBeanIsPresent() {
        assertEquals(1, adapters.size());
        SocialAdapter adapter = adapters.get(0);
        assertInstanceOf(ActivityPubSocialAdapter.class, ClientProxy.unwrap(adapter));
        assertEquals(ACTIVITYPUB, adapter.network());
        assertEquals(Set.of(Visibility.PUBLIC, Visibility.UNLISTED, Visibility.FOLLOWERS),
                adapter.capabilities().visibilities());
        assertEquals(Set.of(Kind.NOTE, Kind.ARTICLE), adapter.capabilities().kinds());
    }

    @Test
    void disabledByConfigIsSkipped() {
        Post post = Post.builder("disabled-1").text("Not sent").build();
        assertSkipped(social.publish("alice", post), ACTIVITYPUB, SkipReason.DISABLED_BY_CONFIG);
    }
}
