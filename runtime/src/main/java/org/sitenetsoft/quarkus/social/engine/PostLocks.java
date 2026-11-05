package org.sitenetsoft.quarkus.social.engine;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

/**
 * Serialises the operations on one post id within this process, without blocking a thread: each operation waits for
 * the previous one's completion (a queue of futures per id). Operations on several nodes are covered by the store's
 * compare-and-set.
 */
final class PostLocks {

    private final Map<String, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();

    <T> Uni<T> withLock(String postId, Supplier<Uni<T>> action) {
        return Uni.createFrom().deferred(() -> {
            CompletableFuture<Void> mine = new CompletableFuture<>();
            CompletableFuture<Void> previous = tails.put(postId, mine);
            Uni<Void> turn = previous == null
                    ? Uni.createFrom().voidItem()
                    // resume on a worker, not inside the previous operation's completion callback
                    : Uni.createFrom().completionStage(previous).emitOn(Infrastructure.getDefaultWorkerPool());
            return turn.chain(() -> action.get())
                    // a waiter cancelled before its turn releases only after its predecessor, keeping the order
                    .onTermination().invoke(() -> (previous == null ? CompletableFuture.<Void>completedFuture(null) : previous)
                            .whenComplete((v, t) -> {
                                tails.remove(postId, mine);
                                mine.complete(null);
                            }));
        });
    }
}
