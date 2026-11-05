package org.sitenetsoft.quarkus.social.events;

import org.sitenetsoft.quarkus.social.Network;
import org.sitenetsoft.quarkus.social.store.DeliveryProgress;

/** CDI event: new delivery counts for a post on a network ({@code progress.pending() == 0}: finished). */
public record SocialDeliveryProgress(String postId, Network network, DeliveryProgress progress) {
}
