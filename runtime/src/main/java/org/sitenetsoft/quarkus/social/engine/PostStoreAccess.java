package org.sitenetsoft.quarkus.social.engine;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import org.sitenetsoft.quarkus.social.Network;
import org.sitenetsoft.quarkus.social.SocialException;
import org.sitenetsoft.quarkus.social.store.ReactiveSocialPostStore;
import org.sitenetsoft.quarkus.social.store.SocialPostStore;
import org.sitenetsoft.quarkus.social.store.StoredPost;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

/**
 * The post store as the engine uses it: reactive, whichever form the app provided (the reactive one wins; a blocking
 * store runs on a worker thread, never on the event loop), plus the compare-and-set write loop.
 */
final class PostStoreAccess {

    /** Writes retried after a version conflict before giving up. */
    static final int MAX_ATTEMPTS = 10;

    private final ReactiveSocialPostStore reactive;
    private final SocialPostStore blocking;

    private PostStoreAccess(ReactiveSocialPostStore reactive, SocialPostStore blocking) {
        this.reactive = reactive;
        this.blocking = blocking;
    }

    static PostStoreAccess reactive(ReactiveSocialPostStore store) {
        return new PostStoreAccess(store, null);
    }

    static PostStoreAccess blocking(SocialPostStore store) {
        return new PostStoreAccess(null, store);
    }

    /** The store's class, for logs. */
    String describe() {
        return (reactive != null ? reactive : blocking).getClass().getName();
    }

    Uni<Optional<StoredPost>> find(String postId) {
        return reactive != null ? reactive.find(postId) : offload(() -> blocking.find(postId));
    }

    Uni<Boolean> insert(StoredPost post) {
        return reactive != null ? reactive.insert(post) : offload(() -> blocking.insert(post));
    }

    Uni<Optional<String>> findPostIdByRef(Network network, String ref) {
        return reactive != null ? reactive.findPostIdByRef(network, ref)
                : offload(() -> blocking.findPostIdByRef(network, ref));
    }

    Uni<List<StoredPost>> recent(int limit) {
        return reactive != null ? reactive.recent(limit) : offload(() -> blocking.recent(limit));
    }

    /**
     * Applies {@code change} to {@code known} and stores it with compare-and-set. On a version conflict (another node,
     * or delivery progress written meanwhile) it re-reads the post and applies {@code change} to the fresh copy, so
     * each change merges per network.
     *
     * @return the stored post, with its new version
     */
    Uni<StoredPost> save(StoredPost known, UnaryOperator<StoredPost> change) {
        return save(known, change, 1);
    }

    private Uni<StoredPost> save(StoredPost known, UnaryOperator<StoredPost> change, int attempt) {
        StoredPost next = change.apply(known);
        return replace(next, known.version()).chain(replaced -> {
            if (replaced) {
                return Uni.createFrom().item(next.withVersion(known.version() + 1));
            }
            if (attempt >= MAX_ATTEMPTS) {
                return Uni.createFrom().failure(new SocialException("post " + known.postId()
                        + " was changed concurrently " + attempt + " times; its state may be incomplete"));
            }
            return find(known.postId()).chain(fresh -> fresh
                    .map(f -> save(f, change, attempt + 1))
                    .orElseGet(() -> Uni.createFrom().failure(
                            new SocialException("post " + known.postId() + " disappeared from the post store"))));
        });
    }

    Uni<Boolean> replace(StoredPost post, long expectedVersion) {
        return reactive != null ? reactive.replace(post, expectedVersion)
                : offload(() -> blocking.replace(post, expectedVersion));
    }

    private static <T> Uni<T> offload(Supplier<T> call) {
        return Uni.createFrom().item(call).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}
