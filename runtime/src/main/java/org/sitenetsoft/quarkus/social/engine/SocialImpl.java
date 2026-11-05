package org.sitenetsoft.quarkus.social.engine;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.sitenetsoft.quarkus.social.Capabilities;
import org.sitenetsoft.quarkus.social.Network;
import org.sitenetsoft.quarkus.social.Post;
import org.sitenetsoft.quarkus.social.PublishResult;
import org.sitenetsoft.quarkus.social.Social;
import org.sitenetsoft.quarkus.social.SocialException;
import org.sitenetsoft.quarkus.social.store.StoredPost;

import io.smallrye.mutiny.Uni;
import io.vertx.core.Context;

/** Blocking {@link Social}: awaits the {@link PublishEngine}, refusing to block the Vert.x event loop. */
@ApplicationScoped
public class SocialImpl implements Social {

    @Inject
    PublishEngine engine;

    @Override
    public PublishResult publish(String account, Post post) {
        return await("publish", () -> engine.publish(account, post));
    }

    @Override
    public PublishResult update(String account, Post post) {
        return await("update", () -> engine.update(account, post));
    }

    @Override
    public PublishResult delete(String account, String postId) {
        return await("delete", () -> engine.delete(account, postId));
    }

    @Override
    public Optional<StoredPost> status(String postId) {
        return await("status", () -> engine.status(postId));
    }

    @Override
    public Map<Network, Capabilities> capabilities() {
        return engine.capabilities();
    }

    /**
     * Waits without a bound and without cancelling: every adapter call is bounded by the timeout, and the work runs to
     * completion (state saved) even if this thread is interrupted.
     */
    private <T> T await(String operation, Supplier<Uni<T>> call) {
        if (Context.isOnEventLoopThread()) {
            throw new IllegalStateException("Social." + operation + " blocks and must not run on the Vert.x event loop; "
                    + "use ReactiveSocial there, or a worker thread (@Blocking)");
        }
        CompletableFuture<T> outcome = call.get().subscribeAsCompletionStage();
        try {
            return outcome.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SocialException("interrupted while waiting for Social." + operation
                    + "; it carries on, and status(postId) shows what was written", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new SocialException("Social." + operation + " failed: " + cause, cause);
        }
    }
}
