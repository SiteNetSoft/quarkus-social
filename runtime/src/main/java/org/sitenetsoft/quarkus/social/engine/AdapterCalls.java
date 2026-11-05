package org.sitenetsoft.quarkus.social.engine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.jboss.logging.Logger;
import org.sitenetsoft.quarkus.social.FailureCode;
import org.sitenetsoft.quarkus.social.Network;
import org.sitenetsoft.quarkus.social.NetworkResult;
import org.sitenetsoft.quarkus.social.SkipReason;
import org.sitenetsoft.quarkus.social.spi.AdapterRequest;
import org.sitenetsoft.quarkus.social.spi.SocialAdapter;
import org.sitenetsoft.quarkus.social.store.NetworkState;

import io.smallrye.mutiny.Uni;

/**
 * Calls adapters in parallel, each bounded by the timeout, and turns everything that can go wrong in an adapter into a
 * per-network result: the engine never fails because of a network.
 */
final class AdapterCalls {

    private static final Logger LOG = Logger.getLogger(AdapterCalls.class);

    /** Which adapter method a call uses. */
    enum Operation {
        PUBLISH(SocialAdapter::publish),
        UPDATE(SocialAdapter::update),
        DELETE(SocialAdapter::delete);

        final BiFunction<SocialAdapter, AdapterRequest, Uni<NetworkResult>> method;

        Operation(BiFunction<SocialAdapter, AdapterRequest, Uni<NetworkResult>> method) {
            this.method = method;
        }
    }

    private final Duration timeout;

    AdapterCalls(Duration timeout) {
        this.timeout = timeout;
    }

    /**
     * Runs every call at once; the result has one entry per call, whatever happens.
     *
     * @param calls per adapter, the request to send given the account's linked identity
     */
    Uni<Map<Network, NetworkResult>> all(Map<SocialAdapter, Function<String, AdapterRequest>> calls, String account, Operation op) {
        if (calls.isEmpty()) {
            return Uni.createFrom().item(Map.of());
        }
        List<Uni<NetworkResult>> unis = new ArrayList<>();
        calls.forEach((adapter, request) -> unis.add(call(adapter, account, op, request)));
        return Uni.combine().all().unis(unis).with(NetworkResult.class, results -> {
            Map<Network, NetworkResult> byNetwork = new LinkedHashMap<>();
            results.forEach(r -> byNetwork.put(r.network(), r));
            return byNetwork;
        });
    }

    private Uni<NetworkResult> call(SocialAdapter adapter, String account, Operation op,
            Function<String, AdapterRequest> request) {
        Network network = adapter.network();
        Optional<String> unavailable;
        try {
            unavailable = adapter.unavailableReason();
        } catch (RuntimeException e) {
            return Uni.createFrom().item(internal(network, op, e));
        }
        if (unavailable != null && unavailable.isPresent()) {
            return Uni.createFrom().item(new NetworkResult.Skipped(network, SkipReason.NOT_LINKED, unavailable.get()));
        }
        Uni<NetworkResult> answer = Uni.createFrom().deferred(() -> adapter.linkedIdentity(account))
                .onItemOrFailure().transformToUni((identity, failure) -> {
                    if (failure != null) {
                        return Uni.createFrom().item(rejected(network, failure));
                    }
                    if (identity == null || identity.isEmpty()) {
                        return Uni.createFrom().item(new NetworkResult.Skipped(network, SkipReason.NOT_LINKED,
                                "account " + account + " is not linked to " + network));
                    }
                    AdapterRequest req = request.apply(identity.get());
                    return Uni.createFrom().deferred(() -> op.method.apply(adapter, req))
                            .map(r -> checked(network, op, r))
                            .onFailure().recoverWithItem(e -> internal(network, op, e));
                });
        return answer.ifNoItem().after(timeout).recoverWithItem(() -> {
            LOG.warnf("%s on %s gave no answer within %s", op, network, timeout);
            return new NetworkResult.Failed(network, FailureCode.TIMEOUT,
                    "no answer from " + network + " within " + timeout, true, Map.of());
        });
    }

    /** A null or other-network result, or a reserved ref name, is an adapter bug: reported as INTERNAL rather than stored as is. */
    private static NetworkResult checked(Network network, Operation op, NetworkResult r) {
        if (r == null) {
            return internal(network, op, new NullPointerException(op + " returned a null result"));
        }
        if (!network.equals(r.network())) {
            return internal(network, op, new IllegalStateException(
                    op + " returned a result for network " + r.network()));
        }
        Map<String, String> refs = switch (r) {
            case NetworkResult.Published p -> p.refs();
            case NetworkResult.Failed f -> f.refs();
            case NetworkResult.Skipped s -> Map.of();
        };
        if (refs.containsKey(NetworkState.URL_REF)) {
            return internal(network, op, new IllegalStateException(
                    op + " returned a ref named " + NetworkState.URL_REF + ", which is reserved"));
        }
        return r;
    }

    private static NetworkResult rejected(Network network, Throwable failure) {
        LOG.debugf(failure, "link lookup for %s failed", network);
        String message = failure.getMessage() != null ? failure.getMessage() : failure.getClass().getName();
        return new NetworkResult.Failed(network, FailureCode.REJECTED, message, false, Map.of());
    }

    private static NetworkResult internal(Network network, Operation op, Throwable e) {
        LOG.warnf(e, "%s on %s failed unexpectedly", op, network);
        String reason = e.getMessage() == null ? e.getClass().getName() : e.getClass().getName() + ": " + e.getMessage();
        return new NetworkResult.Failed(network, FailureCode.INTERNAL, reason.replaceAll("\\s*\\R\\s*", " "), true,
                Map.of());
    }
}
