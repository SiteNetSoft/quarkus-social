package org.sitenetsoft.quarkus.social.spi;

import java.util.Optional;

import org.sitenetsoft.quarkus.social.Capabilities;
import org.sitenetsoft.quarkus.social.Network;
import org.sitenetsoft.quarkus.social.NetworkResult;

import io.smallrye.mutiny.Uni;

/**
 * One network's publishing side, a CDI bean picked up by the engine (several adapters, one per network).
 * <p>
 * Methods must not block the calling thread: they return a {@link Uni} and do their I/O asynchronously or on a worker
 * thread. The engine applies the {@code quarkus.social.timeout} to every call and turns a failed {@code Uni} into a
 * {@code Failed(INTERNAL)} result, so an adapter reports expected network problems as {@link NetworkResult.Failed} and
 * never needs to throw.
 */
public interface SocialAdapter {

    Network network();

    Capabilities capabilities();

    /**
     * Why this adapter can't publish at all right now (for example a mode without app accounts); when present the
     * engine reports {@code Skipped(NOT_LINKED, reason)} for every call without calling the adapter.
     */
    default Optional<String> unavailableReason() {
        return Optional.empty();
    }

    /**
     * The network identity (ActivityPub username, DID, …) the app account publishes as. Empty: the account is not
     * linked to this network, and the engine reports {@code Skipped(NOT_LINKED)} without calling {@link #publish}. A
     * failed {@code Uni} (for example an ambiguous link) is reported as {@code Failed(REJECTED)} with its message.
     */
    Uni<Optional<String>> linkedIdentity(String account);

    Uni<NetworkResult> publish(AdapterRequest req);

    Uni<NetworkResult> update(AdapterRequest req);

    /** {@code req.post()} is the stored snapshot. A {@code Published} result means the post is gone there. */
    Uni<NetworkResult> delete(AdapterRequest req);
}
