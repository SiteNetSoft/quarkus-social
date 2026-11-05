package org.sitenetsoft.quarkus.social.activitypub.deployment;

import org.sitenetsoft.quarkus.social.activitypub.ActivityPubSocialAdapter;
import org.sitenetsoft.quarkus.social.activitypub.DefaultActivityPubAccountLinks;
import org.sitenetsoft.quarkus.social.activitypub.DeliveryTracker;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;

class SocialActivityPubProcessor {

    static final String FEATURE = "social-activitypub";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /** The adapter (found by the engine through {@code @All}), its delivery-event observer and its account links. */
    @BuildStep
    AdditionalBeanBuildItem beans() {
        return AdditionalBeanBuildItem.builder()
                .addBeanClasses(ActivityPubSocialAdapter.class, DeliveryTracker.class,
                        DefaultActivityPubAccountLinks.class)
                .setUnremovable()
                .build();
    }
}
