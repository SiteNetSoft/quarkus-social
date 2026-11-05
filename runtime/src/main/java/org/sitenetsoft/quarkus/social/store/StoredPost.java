package org.sitenetsoft.quarkus.social.store;

import java.util.Map;
import java.util.Objects;

import org.sitenetsoft.quarkus.social.Kind;
import org.sitenetsoft.quarkus.social.Network;
import org.sitenetsoft.quarkus.social.Post;
import org.sitenetsoft.quarkus.social.Visibility;

/**
 * What the post store holds per post id: the owning account, the post as last published or updated, and per network
 * what was written there. {@code version} is the compare-and-set counter of {@link SocialPostStore#replace}.
 */
public record StoredPost(String postId, String account, Kind kind, Visibility visibility, Post snapshot,
        Map<Network, NetworkState> networks, long version) {

    public StoredPost {
        Objects.requireNonNull(postId, "postId");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(snapshot, "snapshot");
        networks = Map.copyOf(networks);
    }

    public StoredPost withVersion(long newVersion) {
        return new StoredPost(postId, account, kind, visibility, snapshot, networks, newVersion);
    }
}
