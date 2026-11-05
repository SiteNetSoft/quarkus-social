package org.sitenetsoft.quarkus.social.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private static final String NEW_POST = "delete it and publish a new post";

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
                : PostStoreAccess.blocking(ClientProxy.unwrap(blockingStore.get()));
        calls = new AdapterCalls(config.timeout());
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
            requireAccount(account);
            requirePost(post);
            Map<Network, SocialAdapter> targets = targets(post);
            return locks.withLock(post.id(), () -> loadOrInsert(account, post)
                    .chain(stored -> publishMissing(stored, account, post, targets)));
        });
    }

    /** The stored post, or a new one inserted for this first call; a lost insert race reads the winner's. */
    private Uni<StoredPost> loadOrInsert(String account, Post post) {
        StoredPost fresh = new StoredPost(post.id(), account, post.kind(), post.visibility(), post, Map.of(), 0);
        return store.find(post.id()).chain(found -> {
            if (found.isPresent()) {
                return Uni.createFrom().item(checkRepeat(found.get(), account, post));
            }
            return store.insert(fresh).chain(inserted -> inserted ? Uni.createFrom().item(fresh)
                    : load(post.id()).map(winner -> checkRepeat(winner, account, post)));
        });
    }

    private static StoredPost checkRepeat(StoredPost stored, String account, Post post) {
        checkOwner(stored, account);
        checkCarriable(stored, post);
        return stored;
    }

    private Uni<PublishResult> publishMissing(StoredPost stored, String account, Post post,
            Map<Network, SocialAdapter> targets) {
        PostContent content = PostContent.of(post);
        Map<Network, NetworkResult> results = new LinkedHashMap<>();
        Map<SocialAdapter, Function<String, AdapterRequest>> toCall = new LinkedHashMap<>();
        targets.forEach((network, adapter) -> {
            NetworkState state = stored.networks().get(network);
            if (state != null && state.status() == Status.PUBLISHED) {
                results.put(network, States.republished(state));
            } else {
                toCall.put(adapter, identity -> new AdapterRequest(account, identity, post, content,
                        Optional.ofNullable(state)));
            }
        });
        if (toCall.isEmpty()) {
            throw new SocialException("post " + post.id() + " is already published; use update");
        }
        return calls.all(toCall, account, Operation.PUBLISH).chain(called -> {
            results.putAll(called);
            return store.save(stored, current -> withStates(current, current.snapshot(), called, States::afterPublish))
                    .map(saved -> new PublishResult(post.id(), results));
        });
    }

    // ---- update and delete ----

    public Uni<PublishResult> update(String account, Post post) {
        transactions.warnIfActive("update");
        return Uni.createFrom().deferred(() -> {
            requireAccount(account);
            requirePost(post);
            requireAdapters();
            return locks.withLock(post.id(), () -> load(post.id()).chain(stored -> {
                checkOwner(stored, account);
                checkCarriable(stored, post);
                return everywhere(stored, account, Operation.UPDATE, post,
                        state -> state != null && state.status() == Status.PUBLISHED, States::afterUpdate);
            }));
        });
    }

    public Uni<PublishResult> delete(String account, String postId) {
        transactions.warnIfActive("delete");
        return Uni.createFrom().deferred(() -> {
            requireAccount(account);
            if (postId == null || postId.isBlank()) {
                throw new SocialException("post id must not be blank");
            }
            requireAdapters();
            return locks.withLock(postId, () -> load(postId).chain(stored -> {
                checkOwner(stored, account);
                return everywhere(stored, account, Operation.DELETE, stored.snapshot(), States::written,
                        States::afterDelete);
            }));
        });
    }

    /**
     * Calls {@code op} on every network where {@code has} holds; the others are {@code Skipped(NOT_PUBLISHED_HERE)}.
     * The stored snapshot becomes {@code post}.
     */
    private Uni<PublishResult> everywhere(StoredPost stored, String account, Operation op, Post post,
            Predicate<NetworkState> has, BiFunction<NetworkResult, NetworkState, NetworkState> after) {
        PostContent content = PostContent.of(post);
        Set<Network> networks = new LinkedHashSet<>(adapters.keySet());
        networks.addAll(stored.networks().keySet());
        Map<Network, NetworkResult> results = new LinkedHashMap<>();
        Map<SocialAdapter, Function<String, AdapterRequest>> toCall = new LinkedHashMap<>();
        for (Network network : networks) {
            NetworkState state = stored.networks().get(network);
            SocialAdapter adapter = adapters.get(network);
            if (!has.test(state)) {
                results.put(network, new NetworkResult.Skipped(network, SkipReason.NOT_PUBLISHED_HERE,
                        "post " + stored.postId() + " is not published on " + network));
            } else if (adapter == null) {
                results.put(network, new NetworkResult.Failed(network, FailureCode.UNAVAILABLE,
                        "no active adapter for network " + network, true, state.refs()));
            } else {
                toCall.put(adapter, identity -> new AdapterRequest(account, identity, post, content,
                        Optional.of(state)));
            }
        }
        return calls.all(toCall, account, op).chain(called -> {
            results.putAll(called);
            return store.save(stored, current -> withStates(current, post, called, after))
                    .map(saved -> new PublishResult(stored.postId(), results));
        });
    }

    // ---- status, capabilities, adapter callbacks ----

    public Uni<Optional<StoredPost>> status(String postId) {
        return store.find(postId);
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

    private static void requireAccount(String account) {
        if (account == null || account.isBlank()) {
            throw new SocialException("account must not be blank");
        }
    }

    private static void requirePost(Post post) {
        if (post == null) {
            throw new SocialException("post must not be null");
        }
    }

    private static void checkOwner(StoredPost stored, String account) {
        if (!stored.account().equals(account)) {
            throw new SocialException("post " + stored.postId() + " belongs to another account");
        }
    }

    /** Kind and visibility can't change in place on every network, so the post must be replaced instead. */
    private static void checkCarriable(StoredPost stored, Post post) {
        if (stored.kind() != post.kind()) {
            throw new SocialException("post " + stored.postId() + ": the kind can't change from " + stored.kind()
                    + " to " + post.kind() + "; " + NEW_POST);
        }
        if (stored.visibility() != post.visibility()) {
            throw new SocialException("post " + stored.postId() + ": the visibility can't change from "
                    + stored.visibility() + " to " + post.visibility() + "; " + NEW_POST);
        }
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
