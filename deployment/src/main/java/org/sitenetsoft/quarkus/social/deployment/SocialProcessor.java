package org.sitenetsoft.quarkus.social.deployment;

import org.sitenetsoft.quarkus.social.engine.PublishEngine;
import org.sitenetsoft.quarkus.social.engine.ReactiveSocialImpl;
import org.sitenetsoft.quarkus.social.engine.SocialImpl;
import org.sitenetsoft.quarkus.social.spi.ReactiveSocialAccountLinks;
import org.sitenetsoft.quarkus.social.spi.SocialAccountLinks;
import org.sitenetsoft.quarkus.social.spi.SocialAdapter;
import org.sitenetsoft.quarkus.social.store.InMemorySocialPostStore;
import org.sitenetsoft.quarkus.social.store.ReactiveSocialPostStore;
import org.sitenetsoft.quarkus.social.store.SocialPostStore;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;

class SocialProcessor {

    static final String FEATURE = "social";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    AdditionalBeanBuildItem beans() {
        return AdditionalBeanBuildItem.builder()
                .addBeanClasses(PublishEngine.class, SocialImpl.class, ReactiveSocialImpl.class,
                        InMemorySocialPostStore.class)
                .setUnremovable()
                .build();
    }

    /**
     * SPI beans are looked up programmatically; keep them. A missing {@code SocialAdapter} is not a build error
     * (adapters come later in an app's life): the engine warns at startup instead.
     */
    @BuildStep
    UnremovableBeanBuildItem spiBeans() {
        return UnremovableBeanBuildItem.beanTypes(SocialAdapter.class, SocialPostStore.class,
                ReactiveSocialPostStore.class, SocialAccountLinks.class, ReactiveSocialAccountLinks.class);
    }
}
