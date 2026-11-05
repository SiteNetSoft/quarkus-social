package org.sitenetsoft.quarkus.social.store;

/** Asynchronous delivery counts of one post on one network; {@code pending == 0} means delivery is finished. */
public record DeliveryProgress(int delivered, int failed, int pending) {

    public DeliveryProgress {
        if (delivered < 0 || failed < 0 || pending < 0) {
            throw new IllegalArgumentException("delivery counts must not be negative");
        }
    }
}
