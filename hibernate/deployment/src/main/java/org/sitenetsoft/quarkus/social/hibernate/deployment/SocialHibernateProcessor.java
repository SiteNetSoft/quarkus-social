package org.sitenetsoft.quarkus.social.hibernate.deployment;

import org.sitenetsoft.quarkus.social.hibernate.HibernateSocialPostStore;
import org.sitenetsoft.quarkus.social.hibernate.SocialNetworkStateEntity;
import org.sitenetsoft.quarkus.social.hibernate.SocialPostEntity;
import org.sitenetsoft.quarkus.social.hibernate.SocialRefEntity;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.AdditionalIndexedClassesBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;

class SocialHibernateProcessor {

    static final String FEATURE = "social-hibernate";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /** The entities join the application's JPA model by being indexed, as in quarkus-activitypub-hibernate. */
    @BuildStep
    AdditionalIndexedClassesBuildItem entities() {
        return new AdditionalIndexedClassesBuildItem(SocialPostEntity.class.getName(),
                SocialNetworkStateEntity.class.getName(), SocialNetworkStateEntity.Key.class.getName(),
                SocialRefEntity.class.getName(), SocialRefEntity.Key.class.getName());
    }

    @BuildStep
    AdditionalBeanBuildItem store() {
        return AdditionalBeanBuildItem.builder().addBeanClass(HibernateSocialPostStore.class).setUnremovable().build();
    }
}
