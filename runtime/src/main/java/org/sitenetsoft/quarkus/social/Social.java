package org.sitenetsoft.quarkus.social;

import java.util.Map;
import java.util.Optional;

import org.sitenetsoft.quarkus.social.store.StoredPost;

/**
 * Publish posts to every active network (blocking API; {@link ReactiveSocial} is the Mutiny form of the same
 * implementation).
 * <p>
 * The methods block, so they must not be called on the Vert.x event loop (they throw
 * {@link IllegalStateException} there). They must not run inside the app's transaction either: networks are remote
 * calls (checked in dev mode with a warning).
 * <p>
 * A network problem is always a per-network {@link NetworkResult.Failed}, never an exception; a {@link SocialException}
 * means a caller error (unknown or already published post id, an edit no network can carry, no active adapter).
 */
public interface Social {

    /**
     * Publishes to every active network ({@link Post#networks()} narrows it). Called again with the same post id it
     * tries only the networks where the post isn't published yet; it throws when every one already has it.
     */
    PublishResult publish(String account, Post post);

    /** Sends the new content to the networks that have the post; kind and visibility changes are refused. */
    PublishResult update(String account, Post post);

    /** Removes the post from every network that has it. */
    PublishResult delete(String account, String postId);

    /** The last known per-network state, delivery progress included. */
    Optional<StoredPost> status(String postId);

    /** Capabilities of the active adapters. */
    Map<Network, Capabilities> capabilities();
}
