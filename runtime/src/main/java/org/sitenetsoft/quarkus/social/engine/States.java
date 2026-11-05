package org.sitenetsoft.quarkus.social.engine;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.sitenetsoft.quarkus.social.FailureCode;
import org.sitenetsoft.quarkus.social.Network;
import org.sitenetsoft.quarkus.social.NetworkResult;
import org.sitenetsoft.quarkus.social.store.DeliveryProgress;
import org.sitenetsoft.quarkus.social.store.NetworkState;
import org.sitenetsoft.quarkus.social.store.NetworkState.Status;

/** Conversions between per-call {@link NetworkResult}s and stored {@link NetworkState}s. */
final class States {

    static final String IN_PROGRESS_DETAIL = "another publish of this post is in progress";

    private States() {
    }

    /**
     * A publish run's lease on a network, written before its adapter is called: {@code FAILED(IN_PROGRESS)} keeping
     * what {@code previous} knew (refs, delivery), so an abandoned run looks like a failure once the lease expires.
     */
    static NetworkState lease(Network network, NetworkState previous, Instant now) {
        return new NetworkState(network, Status.FAILED, previous == null ? Map.of() : previous.refs(),
                Optional.of(FailureCode.IN_PROGRESS.name()), Optional.of(IN_PROGRESS_DETAIL), now, delivery(previous));
    }

    /** A lease younger than {@code leaseTime}: another run is calling that network now. */
    static boolean liveLease(NetworkState state, Instant now, Duration leaseTime) {
        return state != null && state.status() == Status.FAILED
                && state.code().filter(FailureCode.IN_PROGRESS.name()::equals).isPresent()
                && state.at().plus(leaseTime).isAfter(now);
    }

    /**
     * The state after a {@code publish} call: what the result says, keeping the refs and delivery progress already
     * known (a retry may report only what it added; progress may have arrived meanwhile).
     */
    static NetworkState afterPublish(NetworkResult result, NetworkState current) {
        NetworkState live = current == null || current.status() == Status.DELETED ? null : current;
        return switch (result) {
            case NetworkResult.Published p -> state(p, Status.PUBLISHED, live);
            case NetworkResult.Skipped s -> new NetworkState(s.network(), Status.SKIPPED, refs(live, Map.of()),
                    Optional.of(s.reason().name()), Optional.ofNullable(s.detail()), Instant.now(), delivery(live));
            case NetworkResult.Failed f -> new NetworkState(f.network(), Status.FAILED, refs(live, f.refs()),
                    Optional.of(f.code().name()), Optional.ofNullable(f.reason()), Instant.now(), delivery(live));
        };
    }

    /** The state after an {@code update}: a {@code Published} result refreshes it, anything else leaves it as it was. */
    static NetworkState afterUpdate(NetworkResult result, NetworkState current) {
        return result instanceof NetworkResult.Published p ? state(p, Status.PUBLISHED, current) : current;
    }

    /** The state after a {@code delete}: a {@code Published} result means gone, anything else leaves it as it was. */
    static NetworkState afterDelete(NetworkResult result, NetworkState current) {
        return result instanceof NetworkResult.Published p ? state(p, Status.DELETED, current) : current;
    }

    /** The stored {@code Published} result, returned again by a repeated publish, marked as not re-sent. */
    static NetworkResult.Published republished(NetworkState state) {
        Map<String, String> refs = new LinkedHashMap<>(state.refs());
        Optional<URI> url = Optional.ofNullable(refs.remove(NetworkState.URL_REF)).map(URI::create);
        return new NetworkResult.Published(state.network(), url, refs, state.detail(), false);
    }

    /** Partially or fully written: something exists on the network that a delete must remove. */
    static boolean written(NetworkState state) {
        return state != null && (state.status() == Status.PUBLISHED
                || state.status() != Status.DELETED && !refsWithoutUrl(state).isEmpty());
    }

    private static NetworkState state(NetworkResult.Published p, Status status, NetworkState current) {
        Map<String, String> refs = new LinkedHashMap<>(refs(current, p.refs()));
        p.url().ifPresent(u -> refs.put(NetworkState.URL_REF, u.toString()));
        return new NetworkState(p.network(), status, refs, Optional.empty(), p.note(), Instant.now(),
                delivery(current));
    }

    private static Map<String, String> refs(NetworkState current, Map<String, String> added) {
        Map<String, String> refs = new LinkedHashMap<>(current == null ? Map.of() : current.refs());
        refs.putAll(added);
        return refs;
    }

    private static Optional<DeliveryProgress> delivery(NetworkState current) {
        return current == null ? Optional.empty() : current.delivery();
    }

    private static Map<String, String> refsWithoutUrl(NetworkState state) {
        Map<String, String> refs = new LinkedHashMap<>(state.refs());
        refs.remove(NetworkState.URL_REF);
        return refs;
    }
}
