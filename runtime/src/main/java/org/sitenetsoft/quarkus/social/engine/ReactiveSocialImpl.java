package org.sitenetsoft.quarkus.social.engine;

import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.sitenetsoft.quarkus.social.Capabilities;
import org.sitenetsoft.quarkus.social.Network;
import org.sitenetsoft.quarkus.social.Post;
import org.sitenetsoft.quarkus.social.PublishResult;
import org.sitenetsoft.quarkus.social.ReactiveSocial;
import org.sitenetsoft.quarkus.social.store.StoredPost;

import io.smallrye.mutiny.Uni;

/** {@link ReactiveSocial}: the {@link PublishEngine} as is. */
@ApplicationScoped
public class ReactiveSocialImpl implements ReactiveSocial {

    @Inject
    PublishEngine engine;

    @Override
    public Uni<PublishResult> publish(String account, Post post) {
        return engine.publish(account, post);
    }

    @Override
    public Uni<PublishResult> update(String account, Post post) {
        return engine.update(account, post);
    }

    @Override
    public Uni<PublishResult> delete(String account, String postId) {
        return engine.delete(account, postId);
    }

    @Override
    public Uni<Optional<StoredPost>> status(String postId) {
        return engine.status(postId);
    }

    @Override
    public Uni<Map<Network, Capabilities>> capabilities() {
        return Uni.createFrom().item(engine::capabilities);
    }
}
