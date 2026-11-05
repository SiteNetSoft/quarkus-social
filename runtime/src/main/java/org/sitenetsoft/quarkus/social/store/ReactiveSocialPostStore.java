package org.sitenetsoft.quarkus.social.store;

import java.util.List;
import java.util.Optional;

import org.sitenetsoft.quarkus.social.Network;

import io.smallrye.mutiny.Uni;

/** Reactive form of {@link SocialPostStore}, same contract; the engine prefers it when the app provides both. */
public interface ReactiveSocialPostStore {

    Uni<Optional<StoredPost>> find(String postId);

    Uni<Boolean> insert(StoredPost post);

    Uni<Boolean> replace(StoredPost post, long expectedVersion);

    Uni<Optional<String>> findPostIdByRef(Network network, String ref);

    Uni<List<StoredPost>> recent(int limit);
}
