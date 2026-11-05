package org.sitenetsoft.quarkus.social.store;

import java.util.List;
import java.util.Optional;

import org.sitenetsoft.quarkus.social.Network;

/**
 * Where the engine records, per post and network, what was written. Implementations must be safe for concurrent use;
 * {@link #insert} and {@link #replace} are atomic (several nodes may share one store).
 */
public interface SocialPostStore {

    /**
     * The longest ref value {@link #findPostIdByRef} resolves. A longer ref is stored with its state (it round-trips
     * through {@link #find}) but never resolves: every store answers empty for it.
     */
    int MAX_INDEXED_REF_LENGTH = 1024;

    Optional<StoredPost> find(String postId);

    /** Adds a new post; false (and nothing changed) when the post id already exists. */
    boolean insert(StoredPost post);

    /**
     * Compare-and-set: replaces the stored post when its version is {@code expectedVersion}, storing it with version
     * {@code expectedVersion + 1} (whatever {@code post.version()} says); false when the version differs or the post
     * is unknown.
     */
    boolean replace(StoredPost post, long expectedVersion);

    /**
     * The post whose state on {@code network} has {@code ref} among its {@code refs} values (the engine's reserved
     * {@code _url} ref excepted); empty for a ref longer than {@link #MAX_INDEXED_REF_LENGTH}.
     */
    Optional<String> findPostIdByRef(Network network, String ref);

    /** At most {@code limit} posts, most recently written first. */
    List<StoredPost> recent(int limit);
}
