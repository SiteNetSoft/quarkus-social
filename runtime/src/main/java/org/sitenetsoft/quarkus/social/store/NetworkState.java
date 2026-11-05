package org.sitenetsoft.quarkus.social.store;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.sitenetsoft.quarkus.social.Network;

/**
 * The last known state of one post on one network.
 *
 * @param refs protocol ids (as strings) of what exists on the network; the human-readable link of a published post
 *        is kept under {@link #URL_REF}
 * @param code the {@code SkipReason} or {@code FailureCode} name for {@code SKIPPED} / {@code FAILED}
 * @param detail the skip detail, failure reason, or the {@code Published} note
 * @param delivery asynchronous delivery progress, for networks that report it
 */
public record NetworkState(Network network, Status status, Map<String, String> refs, Optional<String> code,
        Optional<String> detail, Instant at, Optional<DeliveryProgress> delivery) {

    /** Key in {@link #refs()} of the {@code Published} url. */
    public static final String URL_REF = "url";

    public enum Status { PUBLISHED, SKIPPED, FAILED, DELETED }

    public NetworkState {
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(at, "at");
        refs = Map.copyOf(refs);
        code = Objects.requireNonNull(code, "code");
        detail = Objects.requireNonNull(detail, "detail");
        delivery = Objects.requireNonNull(delivery, "delivery");
    }

    public NetworkState withDelivery(DeliveryProgress progress) {
        return new NetworkState(network, status, refs, code, detail, at, Optional.of(progress));
    }
}
