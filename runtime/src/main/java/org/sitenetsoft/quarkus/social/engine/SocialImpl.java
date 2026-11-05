package org.sitenetsoft.quarkus.social.engine;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.sitenetsoft.quarkus.social.Capabilities;
import org.sitenetsoft.quarkus.social.Network;
import org.sitenetsoft.quarkus.social.Post;
import org.sitenetsoft.quarkus.social.PublishResult;
import org.sitenetsoft.quarkus.social.Social;
import org.sitenetsoft.quarkus.social.SocialConfig;
import org.sitenetsoft.quarkus.social.SocialException;
import org.sitenetsoft.quarkus.social.store.StoredPost;

import io.smallrye.mutiny.TimeoutException;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Context;

/** Blocking {@link Social}: awaits the {@link PublishEngine}, refusing to block the Vert.x event loop. */
@ApplicationScoped
public class SocialImpl implements Social {

    @Inject
    PublishEngine engine;
    @Inject
    SocialConfig config;

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

    private <T> T await(String operation, Supplier<Uni<T>> call) {
        if (Context.isOnEventLoopThread()) {
            throw new IllegalStateException("Social." + operation + " blocks and must not run on the Vert.x event loop; "
                    + "use ReactiveSocial there, or a worker thread (@Blocking)");
        }
        // every adapter is bounded by the timeout; the rest is waiting for the post's lock and the store
        Duration bound = config.timeout().multipliedBy(2).plusSeconds(30);
        try {
            return call.get().await().atMost(bound);
        } catch (TimeoutException e) {
            throw new SocialException("Social." + operation + " did not finish within " + bound
                    + "; status(postId) shows what was written", e);
        }
    }
}
