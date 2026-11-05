package org.sitenetsoft.quarkus.social.deployment.devui;

import org.sitenetsoft.quarkus.social.devui.SocialJsonRpcService;

import io.quarkus.deployment.IsDevelopment;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.devui.spi.JsonRPCProvidersBuildItem;
import io.quarkus.devui.spi.page.CardPageBuildItem;
import io.quarkus.devui.spi.page.Page;

/**
 * The "Social" Dev UI card: active adapters and their capabilities, what adapters contribute (the Bluesky scope needed
 * against the configured one), the last posts with per-network state, and a form that publishes a test post. Dev mode
 * only ({@code IsDevelopment}): neither the card nor its JSON-RPC service exists in a production build.
 */
public class SocialDevUiProcessor {

    @BuildStep(onlyIf = IsDevelopment.class)
    void devUi(BuildProducer<CardPageBuildItem> cards, BuildProducer<JsonRPCProvidersBuildItem> jsonRpc) {
        CardPageBuildItem card = new CardPageBuildItem();
        card.addPage(Page.webComponentPageBuilder()
                .title("Social")
                .icon("font-awesome-solid:share-nodes")
                .componentLink("qwc-social.js"));
        cards.produce(card);
        jsonRpc.produce(new JsonRPCProvidersBuildItem(SocialJsonRpcService.class));
    }
}
