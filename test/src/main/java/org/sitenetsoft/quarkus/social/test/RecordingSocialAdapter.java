package org.sitenetsoft.quarkus.social.test;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

import org.sitenetsoft.quarkus.social.Capabilities;
import org.sitenetsoft.quarkus.social.FailureCode;
import org.sitenetsoft.quarkus.social.Kind;
import org.sitenetsoft.quarkus.social.Network;
import org.sitenetsoft.quarkus.social.NetworkResult;
import org.sitenetsoft.quarkus.social.SkipReason;
import org.sitenetsoft.quarkus.social.Visibility;
import org.sitenetsoft.quarkus.social.spi.AdapterRequest;
import org.sitenetsoft.quarkus.social.spi.SocialAdapter;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

/**
 * A {@link SocialAdapter} that records every request instead of publishing, so app tests can check what would have
 * been published without either protocol. Every account is linked (identity = account) unless {@link #unlink}ed;
 * every call succeeds unless a {@code …Next} method says otherwise for the next call.
 * <p>
 * Not a bean by itself: register it in the test application, for example
 *
 * <pre>
 * &#64;Singleton
 * public class TestNetwork extends RecordingSocialAdapter {
 * }
 * </pre>
 *
 * or with a producer method; subclass with {@code super(new Network("…"))} for several recording networks.
 */
public class RecordingSocialAdapter implements SocialAdapter {

    /** The network of the no-argument constructor. */
    public static final Network TEST = new Network("test");

    public enum Operation { PUBLISH, UPDATE, DELETE }

    public record RecordedRequest(Operation operation, AdapterRequest request) {
    }

    private final Network network;
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final Queue<Function<AdapterRequest, Uni<NetworkResult>>> next = new ConcurrentLinkedQueue<>();
    private final Queue<RuntimeException> linkFailures = new ConcurrentLinkedQueue<>();
    private final Set<String> unlinked = ConcurrentHashMap.newKeySet();
    private volatile String unavailable;
    private volatile CountDownLatch hold;

    public RecordingSocialAdapter() {
        this(TEST);
    }

    public RecordingSocialAdapter(Network network) {
        this.network = Objects.requireNonNull(network, "network");
    }

    @Override
    public Network network() {
        return network;
    }

    /** Everything a network can do; no text limit. */
    @Override
    public Capabilities capabilities() {
        return new Capabilities(EnumSet.allOf(Visibility.class), EnumSet.allOf(Kind.class), true, true, true,
                OptionalInt.empty(), true, false);
    }

    @Override
    public Optional<String> unavailableReason() {
        return Optional.ofNullable(unavailable);
    }

    @Override
    public Uni<Optional<String>> linkedIdentity(String account) {
        RuntimeException failure = linkFailures.poll();
        if (failure != null) {
            return Uni.createFrom().failure(failure);
        }
        return Uni.createFrom().item(unlinked.contains(account) ? Optional.empty() : Optional.of(account));
    }

    @Override
    public Uni<NetworkResult> publish(AdapterRequest req) {
        return answer(Operation.PUBLISH, req);
    }

    @Override
    public Uni<NetworkResult> update(AdapterRequest req) {
        return answer(Operation.UPDATE, req);
    }

    @Override
    public Uni<NetworkResult> delete(AdapterRequest req) {
        return answer(Operation.DELETE, req);
    }

    // ---- what the next call does ----

    /** The next call answers {@code Failed(code)} with no refs. */
    public void failNext(FailureCode code, boolean retryable) {
        failNext(code, retryable, Map.of());
    }

    /** The next call answers {@code Failed(code)} listing {@code refs} as partially written. */
    public void failNext(FailureCode code, boolean retryable, Map<String, String> refs) {
        next.add(req -> Uni.createFrom().item(
                new NetworkResult.Failed(network, code, "failed by the test", retryable, refs)));
    }

    /** The next call answers {@code Skipped(reason)}. */
    public void skipNext(SkipReason reason) {
        next.add(req -> Uni.createFrom().item(new NetworkResult.Skipped(network, reason, "skipped by the test")));
    }

    /** The next call never answers. */
    public void hangNext() {
        next.add(req -> Uni.createFrom().nothing());
    }

    /** The next call fails with {@code e} (as an adapter bug would). */
    public void throwNext(RuntimeException e) {
        next.add(req -> {
            throw e;
        });
    }

    /** The next {@link #linkedIdentity} lookup fails with {@code e}. */
    public void failLinkNext(RuntimeException e) {
        linkFailures.add(e);
    }

    /** {@code account} is not linked to this network until {@link #reset()}. */
    public void unlink(String account) {
        unlinked.add(account);
    }

    /** The adapter reports itself unavailable for every call until {@link #reset()}. */
    public void unavailable(String reason) {
        unavailable = reason;
    }

    /**
     * Calls (recorded at once) answer only after {@code release} is counted down, on a worker thread, until
     * {@link #reset()}.
     */
    public void holdUntil(CountDownLatch release) {
        hold = release;
    }

    /** Every request so far, oldest first. */
    public List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    /** Forgets requests and every pending instruction. */
    public void reset() {
        requests.clear();
        next.clear();
        linkFailures.clear();
        unlinked.clear();
        unavailable = null;
        hold = null;
    }

    private Uni<NetworkResult> answer(Operation operation, AdapterRequest req) {
        requests.add(new RecordedRequest(operation, req));
        Function<AdapterRequest, Uni<NetworkResult>> behaviour = next.poll();
        Uni<NetworkResult> answer = behaviour != null ? behaviour.apply(req)
                : Uni.createFrom().item(() -> published(req));
        CountDownLatch release = hold;
        if (release == null) {
            return answer;
        }
        return Uni.createFrom().item(() -> {
            try {
                release.await();
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while held", e);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool()).chain(() -> answer);
    }

    private NetworkResult published(AdapterRequest req) {
        String id = URLEncoder.encode(req.post().id(), StandardCharsets.UTF_8);
        URI url = URI.create("https://recording.example/" + network + "/" + id);
        return new NetworkResult.Published(network, Optional.of(url), Map.of("id", network + ":" + req.post().id()),
                Optional.empty(), true);
    }
}
