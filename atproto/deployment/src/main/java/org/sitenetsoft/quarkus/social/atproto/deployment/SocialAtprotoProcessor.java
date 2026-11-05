package org.sitenetsoft.quarkus.social.atproto.deployment;

import org.sitenetsoft.quarkus.atproto.oauth.OAuthBuildTimeConfig;
import org.sitenetsoft.quarkus.social.atproto.AtprotoDevUiContribution;
import org.sitenetsoft.quarkus.social.atproto.AtprotoRepo;
import org.sitenetsoft.quarkus.social.atproto.AtprotoSocialAdapter;
import org.sitenetsoft.quarkus.social.atproto.AtprotoWrites;
import org.sitenetsoft.quarkus.social.atproto.DefaultAtprotoAccountLinks;
import org.sitenetsoft.quarkus.social.atproto.DefaultStandardSitePublications;
import org.sitenetsoft.quarkus.social.atproto.ScopeAdvisor;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;

class SocialAtprotoProcessor {

    static final String FEATURE = "social-atproto";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /**
     * The adapter (found by the engine through {@code @All}), its writers, account links, default Standard.site
     * publications and the startup scope check. Nothing when the AT Protocol OAuth client is disabled: there are no
     * sessions to write with.
     */
    @BuildStep
    void beans(OAuthBuildTimeConfig oauth, BuildProducer<AdditionalBeanBuildItem> beans) {
        if (!oauth.enabled()) {
            return;
        }
        beans.produce(AdditionalBeanBuildItem.builder()
                .addBeanClasses(AtprotoSocialAdapter.class, AtprotoWrites.class, AtprotoRepo.class,
                        DefaultAtprotoAccountLinks.class, DefaultStandardSitePublications.class, ScopeAdvisor.class,
                        AtprotoDevUiContribution.class)
                .setUnremovable()
                .build());
    }
}
