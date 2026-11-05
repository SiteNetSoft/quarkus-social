package org.sitenetsoft.quarkus.social.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;
import org.sitenetsoft.quarkus.social.Capabilities;
import org.sitenetsoft.quarkus.social.FailureCode;
import org.sitenetsoft.quarkus.social.Network;
import org.sitenetsoft.quarkus.social.NetworkResult;
import org.sitenetsoft.quarkus.social.Post;
import org.sitenetsoft.quarkus.social.PublishResult;
import org.sitenetsoft.quarkus.social.SkipReason;
import org.sitenetsoft.quarkus.social.SocialConfig;
import org.sitenetsoft.quarkus.social.SocialException;
import org.sitenetsoft.quarkus.social.content.PostContent;
import org.sitenetsoft.quarkus.social.engine.AdapterCalls.Operation;
import org.sitenetsoft.quarkus.social.events.SocialDeliveryProgress;
import org.sitenetsoft.quarkus.social.spi.AdapterRequest;
import org.sitenetsoft.quarkus.social.spi.SocialAdapter;
import org.sitenetsoft.quarkus.social.store.DeliveryProgress;
import org.sitenetsoft.quarkus.social.store.NetworkState;
import org.sitenetsoft.quarkus.social.store.NetworkState.Status;
import org.sitenetsoft.quarkus.social.store.ReactiveSocialPostStore;
import org.sitenetsoft.quarkus.social.store.SocialPostStore;
import org.sitenetsoft.quarkus.social.store.StoredPost;

import io.quarkus.arc.All;
import io.quarkus.arc.ClientProxy;
import io.quarkus.arc.InjectableInstance;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;

/**
 * The one implementation behind {@code Social} and {@code ReactiveSocial}: picks the target networks, calls their
 * adapters in parallel, and records per network what was written so a repeated call never sends twice.
 * <p>
 * Adapters also use it: {@link #recordProgress} for asynchronous delivery counts, {@link #findPostIdByRef} to map a
 * protocol id back to a post (both go through the store the app configured, blocking or reactive).
 */
@ApplicationScoped
public class PublishEngine {

    private static final Logger LOG = Logger.getLogger(PublishEngine.class);
    private static final Duration LEASE_MARGIN = Duration.ofSeconds(30);

    @Inject
    @All
    List<SocialAdapter> adapterBeans;
    @Inject
    InjectableInstance<SocialPostStore> blockingStore;
    @Inject
    InjectableInstance<ReactiveSocialPostStore> reactiveStore;
    @Inject
    SocialConfig config;
    @Inject
    Event<SocialDeliveryProgress> progressEvents;

    private final PostLocks locks = new PostLocks();
    private final TransactionCheck transactions = new TransactionCheck();
    private Map<Network, SocialAdapter> adapters;
    private PostStoreAccess store;
    private AdapterCalls calls;
    /** How long a publish run's IN_PROGRESS lease on a network blocks other runs. */
    private Duration leaseTime;

    @PostConstruct
    void init() {
        Map<Network, SocialAdapter> byNetwork = new LinkedHashMap<>();
        for (SocialAdapter adapter : adapterBeans) {
            SocialAdapter other = byNetwork.putIfAbsent(adapter.network(), adapter);
            if (other != null) {
                throw new IllegalStateException("two SocialAdapter beans for network " + adapter.network() + ": "
                        + ClientProxy.unwrap(other).getClass().getName() + " and "
                        + ClientProxy.unwrap(adapter).getClass().getName());
            }
        }
        adapters = Collections.unmodifiableMap(byNetwork);
        // the reactive store wins; the in-memory default is unwrapped so its production WARN runs at boot
        store = reactiveStore.isResolvable() ? PostStoreAccess.reactive(reactiveStore.get())
                : PostStoreAccess.blocking(blockingStore.getHandle().getBean().isDefaultBean()
                        ? ClientProxy.unwrap(blockingStore.get()) : blockingStore.get());
        calls = new AdapterCalls(config.timeout());
        leaseTime = config.timeout().plus(LEASE_MARGIN);
    }

    void onStart(@Observes StartupEvent event) {
        if (adapters.isEmpty()) {
            LOG.warn("quarkus-social found no SocialAdapter bean: publish fails until a network adapter is added "
                    + "(quarkus-social-activitypub, quarkus-social-atproto or your own)");
        } else {
            LOG.debugf("social networks %s, post store %s", adapters.keySet(), store.describe());
        }
    }

    // ---- publish ----

    public Uni<PublishResult> publish(String account, Post post) {
        transactions.warnIfActive("publish");
        return Uni.createFrom().deferred(() -> {
            Checks.requireAccount(account);
            Checks.requirePost(post);
            Checks.requireContent(post);
            Map<Network, SocialAdapter> targets = targets(post);
            return detached(locks.withLock(post.id(), () -> startPublish(account, post, targets)));
        });
    }

    /**
     * A first call inserts the post with a lease on every target; a repeat call (or a lost insert race) leases the
     * targets that still need the post.
     */
    private Uni<PublishResult> startPublish(String account, Post post, Map<Network, SocialAdapter> targets) {
        Instant now = Instant.now();
        Map<Network, NetworkState> leases = new LinkedHashMap<>();
        Map<Network, Optional<NetworkState>> all = new LinkedHashMap<>();
        targets.keySet().forEach(n -> {
            leases.put(n, States.lease(n, null, now));
            all.put(n, Optional.empty());
        });
        StoredPost fresh = new StoredPost(post.id(), account, post.kind(), post.visibility(), post, leases, 0);
        return store.find(post.id()).chain(found -> {
            if (found.isPresent()) {
                return leaseAndCall(found.get(), account, post, targets, 1);
            }
            return store.insert(fresh).chain(inserted -> inserted
                    ? callAndSave(fresh, new Plan(Map.of(), all), account, post, targets)
                    : load(post.id()).chain(winner -> leaseAndCall(winner, account, post, targets, 1)));
        });
    }

    /** Per target: already answered (published, or leased by another run), or to call with its previous state. */
    private record Plan(Map<Network, NetworkResult> answered, Map<Network, Optional<NetworkState>> toCall) {
    }

    private Uni<PublishResult> leaseAndCall(StoredPost stored, String account, Post post,
            Map<Network, SocialAdapter> targets, int attempt) {
        Checks.checkRepeat(stored, account, post);
        Instant now = Instant.now();
        Map<Network, NetworkResult> answered = new LinkedHashMap<>();
        Map<Network, Optional<NetworkState>> toCall = new LinkedHashMap<>();
        Map<Network, NetworkState> states = new LinkedHashMap<>(stored.networks());
        for (Network network : targets.keySet()) {
            NetworkState state = stored.networks().get(network);
            if (state != null && state.status() == Status.PUBLISHED) {
                answered.put(network, States.republished(state));
            } else if (States.liveLease(state, now, leaseTime)) {
                answered.put(network, new NetworkResult.Failed(network, FailureCode.IN_PROGRESS,
                        States.IN_PROGRESS_DETAIL, true, Map.of()));
            } else {
                toCall.put(network, Optional.ofNullable(state));
                states.put(network, States.lease(network, state, now));
            }
        }
        if (toCall.isEmpty()) {
            if (answered.values().stream().allMatch(r -> r instanceof NetworkResult.Published)) {
                throw new SocialException("post " + post.id() + " is already published; use update");
            }
            return Uni.createFrom().item(new PublishResult(post.id(), answered));
        }
        StoredPost leased = copy(stored, stored.snapshot(), states);
        return store.replace(leased, stored.version()).chain(replaced -> {
            if (replaced) {
                // a repeat publish retries the stored post (its publication time, its content): adapters derive
                // protocol ids from it, and content changes go through update
                return callAndSave(leased.withVersion(stored.version() + 1), new Plan(answered, toCall), account,
                        stored.snapshot(), targets);
            }
            if (attempt >= PostStoreAccess.MAX_ATTEMPTS) {
                return Uni.createFrom().failure(new SocialException("post " + post.id()
                        + " was changed concurrently " + attempt + " times; try again"));
            }
            return load(post.id()).chain(fresh -> leaseAndCall(fresh, account, post, targets, attempt + 1));
        });
    }

    /**
     * Calls the leased networks and stores their results. A network another run published meanwhile keeps that
     * run's state (this run's refs are logged as orphaned) and is answered with it.
     */
    private Uni<PublishResult> callAndSave(StoredPost leased, Plan plan, String account, Post post,
            Map<Network, SocialAdapter> targets) {
        PostContent content = PostContent.of(post);
        Map<SocialAdapter, Function<String, AdapterRequest>> toCall = new LinkedHashMap<>();
        plan.toCall().forEach((network, previous) -> toCall.put(targets.get(network),
                identity -> new AdapterRequest(account, identity, post, content, previous)));
        return calls.all(toCall, account, Operation.PUBLISH).chain(called -> {
            Set<Network> lost = ConcurrentHashMap.newKeySet();
            return store.save(leased, current -> {
                lost.clear();
                Map<Network, NetworkState> states = new LinkedHashMap<>(current.networks());
                called.forEach((network, result) -> {
                    NetworkState now = states.get(network);
                    if (now != null && now.status() == Status.PUBLISHED) {
                        lost.add(network);
                    } else {
                        states.put(network, States.afterPublish(result, now));
                    }
                });
                return copy(current, current.snapshot(), states);
            }).map(saved -> {
                Map<Network, NetworkResult> results = new LinkedHashMap<>(plan.answered());
                called.forEach((network, result) -> {
                    if (lost.contains(network)) {
                        LOG.warnf("post %s on %s was published by another run first; this run's result %s is "
                                + "orphaned on the network", post.id(), network, result);
                        results.put(network, States.republished(saved.networks().get(network)));
                    } else {
                        results.put(network, result);
                    }
                });
                return new PublishResult(post.id(), results);
            });
        });
    }

    /**
     * Runs {@code work} to completion once subscribed, whatever the subscriber does: cancelling never interrupts
     * a save that follows the adapter calls (every adapter call and lock wait is bounded by itself).
     */
    private static <T> Uni<T> detached(Uni<T> work) {
        CompletableFuture<T> outcome = new CompletableFuture<>();
        work.subscribe().with(outcome::complete, outcome::completeExceptionally);
        return Uni.createFrom().completionStage(outcome);
    }

    // ---- update and delete ----

    public Uni<PublishResult> update(String account, Post post) {
        transactions.warnIfActive("update");
        return Uni.createFrom().deferred(() -> {
            Checks.requireAccount(account);
            Checks.requirePost(post);
            Checks.requireContent(post);
            requireAdapters();
            return detached(locks.withLock(post.id(), () -> load(post.id()).chain(stored -> {
                Checks.checkOwner(stored, account);
                Checks.checkCarriable(stored, post);
                // the publication time is fixed at the first publish (R19): adapters derive protocol ids from it
                Post edited = withPublishedAt(post, stored.snapshot().publishedAt());
                return everywhere(stored, account, Operation.UPDATE, edited, edited,
                        state -> state != null && state.status() == Status.PUBLISHED, States::afterUpdate);
            })));
        });
    }

    public Uni<PublishResult> delete(String account, String postId) {
        transactions.warnIfActive("delete");
        return Uni.createFrom().deferred(() -> {
            Checks.requireAccount(account);
            if (postId == null || postId.isBlank()) {
                throw new SocialException("post id must not be blank");
            }
            requireAdapters();
            return detached(locks.withLock(postId, () -> load(postId).chain(stored -> {
                Checks.checkOwner(stored, account);
                return everywhere(stored, account, Operation.DELETE, stored.snapshot(), null, States::written,
                        States::afterDelete);
            })));
        });
    }

    /**
     * Calls {@code op} on every network where {@code has} holds; the others are {@code Skipped(NOT_PUBLISHED_HERE)},
     * except a network another run's publish holds a live lease on, which is {@code Failed(IN_PROGRESS)} (retryable)
     * without calling its adapter (R20): that run's save would otherwise overwrite this call's result.
     * The stored snapshot becomes {@code newSnapshot}, or stays as it is (re-read on a conflict) when null.
     */
    private Uni<PublishResult> everywhere(StoredPost stored, String account, Operation op, Post post,
            Post newSnapshot, Predicate<NetworkState> has, BiFunction<NetworkResult, NetworkState, NetworkState> after) {
        PostContent content = PostContent.of(post);
        Set<Network> networks = new LinkedHashSet<>(adapters.keySet());
        networks.addAll(stored.networks().keySet());
        Map<Network, NetworkResult> results = new LinkedHashMap<>();
        Map<SocialAdapter, Function<String, AdapterRequest>> toCall = new LinkedHashMap<>();
        Instant now = Instant.now();
        for (Network network : networks) {
            NetworkState state = stored.networks().get(network);
            SocialAdapter adapter = adapters.get(network);
            if (States.liveLease(state, now, leaseTime)) {
                results.put(network, new NetworkResult.Failed(network, FailureCode.IN_PROGRESS,
                        States.PUBLISH_RUNNING_DETAIL, true, Map.of()));
            } else if (!has.test(state)) {
                results.put(network, new NetworkResult.Skipped(network, SkipReason.NOT_PUBLISHED_HERE,
                        "post " + stored.postId() + " is not published on " + network));
            } else if (adapter == null) {
                results.put(network, new NetworkResult.Failed(network, FailureCode.UNAVAILABLE,
                        "no active adapter for network " + network, true, state.refs()));
            } else {
                Optional<Post> before = op == Operation.UPDATE ? Optional.of(stored.snapshot()) : Optional.empty();
                toCall.put(adapter, identity -> new AdapterRequest(account, identity, post, content,
                        Optional.of(state), before));
            }
        }
        return calls.all(toCall, account, op).chain(called -> {
            results.putAll(called);
            return store.save(stored, current -> withStates(current,
                    newSnapshot != null ? newSnapshot : current.snapshot(), called, after))
                    .map(saved -> new PublishResult(stored.postId(), results));
        });
    }

    // ---- status, capabilities, adapter callbacks ----

    public Uni<Optional<StoredPost>> status(String postId) {
        return store.find(postId);
    }

    /** At most {@code limit} posts, most recently written first (Dev UI and diagnostics). */
    public Uni<List<StoredPost>> recent(int limit) {
        return store.recent(limit);
    }

    public Map<Network, Capabilities> capabilities() {
        Map<Network, Capabilities> capabilities = new LinkedHashMap<>();
        adapters.forEach((network, adapter) -> capabilities.put(network, adapter.capabilities()));
        return Collections.unmodifiableMap(capabilities);
    }

    /**
     * Stores new delivery counts for a post on a network and fires {@link SocialDeliveryProgress}. Counts for a post
     * or network the store doesn't know (yet) are not stored; the event is fired anyway.
     */
    public Uni<Void> recordProgress(String postId, Network network, DeliveryProgress progress) {
        return store.find(postId).chain(found -> {
            if (found.isEmpty() || !found.get().networks().containsKey(network)) {
                LOG.debugf("delivery progress for %s on %s not stored: no state yet", postId, network);
                return Uni.createFrom().voidItem();
            }
            return store.save(found.get(), current -> {
                NetworkState state = current.networks().get(network);
                if (state == null) {
                    return current;
                }
                Map<Network, NetworkState> states = new LinkedHashMap<>(current.networks());
                states.put(network, state.withDelivery(progress));
                return copy(current, current.snapshot(), states);
            }).replaceWithVoid();
        }).invoke(() -> {
            try {
                progressEvents.fire(new SocialDeliveryProgress(postId, network, progress));
            } catch (RuntimeException e) {
                LOG.warnf(e, "a SocialDeliveryProgress observer failed for %s", postId);
            }
        });
    }

    /** The post whose state on {@code network} holds {@code ref} (for example an inbound reply's target). */
    public Uni<Optional<String>> findPostIdByRef(Network network, String ref) {
        return store.findPostIdByRef(network, ref);
    }

    // ---- helpers ----

    private Map<Network, SocialAdapter> targets(Post post) {
        requireAdapters();
        if (post.networks().isEmpty()) {
            return adapters;
        }
        Map<Network, SocialAdapter> targets = new LinkedHashMap<>();
        adapters.forEach((network, adapter) -> {
            if (post.networks().get().contains(network)) {
                targets.put(network, adapter);
            }
        });
        if (targets.isEmpty()) {
            throw new SocialException("none of the post's networks " + post.networks().get()
                    + " has an active SocialAdapter (active: " + adapters.keySet() + ")");
        }
        return targets;
    }

    private void requireAdapters() {
        if (adapters.isEmpty()) {
            throw new SocialException("no active SocialAdapter: add a network adapter such as "
                    + "quarkus-social-activitypub or quarkus-social-atproto");
        }
    }

    private Uni<StoredPost> load(String postId) {
        return store.find(postId).map(found -> found.orElseThrow(
                () -> new SocialException("post " + postId + " is unknown; publish it first")));
    }

    /** {@code post} with another publication time (every other field as it is). */
    private static Post withPublishedAt(Post post, Instant publishedAt) {
        if (post.publishedAt().equals(publishedAt)) {
            return post;
        }
        return Post.builder(post.id()).kind(post.kind()).title(post.title().orElse(null))
                .summary(post.summary().orElse(null)).text(post.text().orElse(null)).html(post.html().orElse(null))
                .url(post.url().orElse(null)).language(post.language().orElse(null)).tags(post.tags())
                .visibility(post.visibility()).sensitive(post.sensitive())
                .contentWarning(post.contentWarning().orElse(null)).publishedAt(publishedAt)
                .networks(post.networks().orElse(null)).atprotoLabels(post.atprotoLabels()).build();
    }

    private static StoredPost withStates(StoredPost current, Post snapshot, Map<Network, NetworkResult> called,
            BiFunction<NetworkResult, NetworkState, NetworkState> after) {
        Map<Network, NetworkState> states = new LinkedHashMap<>(current.networks());
        called.forEach((network, result) -> {
            NetworkState next = after.apply(result, states.get(network));
            if (next != null) {
                states.put(network, next);
            }
        });
        return copy(current, snapshot, states);
    }

    private static StoredPost copy(StoredPost current, Post snapshot, Map<Network, NetworkState> states) {
        return new StoredPost(current.postId(), current.account(), current.kind(), current.visibility(), snapshot,
                states, current.version());
    }
}
