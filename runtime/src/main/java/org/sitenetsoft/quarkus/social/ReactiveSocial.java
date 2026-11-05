package org.sitenetsoft.quarkus.social;

import java.util.Map;
import java.util.Optional;

import org.sitenetsoft.quarkus.social.store.StoredPost;

import io.smallrye.mutiny.Uni;

/** Mutiny form of {@link Social}, same semantics; a caller error is a failed {@code Uni} with {@link SocialException}. */
public interface ReactiveSocial {

    Uni<PublishResult> publish(String account, Post post);

    Uni<PublishResult> update(String account, Post post);

    Uni<PublishResult> delete(String account, String postId);

    Uni<Optional<StoredPost>> status(String postId);

    Uni<Map<Network, Capabilities>> capabilities();
}
