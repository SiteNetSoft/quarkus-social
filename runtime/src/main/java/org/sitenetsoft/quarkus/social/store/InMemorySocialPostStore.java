package org.sitenetsoft.quarkus.social.store;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;
import org.sitenetsoft.quarkus.social.Network;

import io.quarkus.arc.DefaultBean;
import io.quarkus.runtime.LaunchMode;

/** Default {@link SocialPostStore}: process memory, lost on restart, not shared between nodes. */
@DefaultBean
@ApplicationScoped
public class InMemorySocialPostStore implements SocialPostStore {

    private static final Logger LOG = Logger.getLogger(InMemorySocialPostStore.class);
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    /** {@code seq} orders {@link #recent}: the sequence number of the last write. */
    private record Entry(StoredPost post, long seq) {
    }

    private final Map<String, Entry> posts = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    @PostConstruct
    void warnIfProduction() {
        if (LaunchMode.current() == LaunchMode.NORMAL && WARNED.compareAndSet(false, true)) {
            LOG.warn("The in-memory SocialPostStore is in use: publish state is lost on restart, so a retried publish "
                    + "can post twice. Provide a SocialPostStore bean (for example quarkus-social-hibernate).");
        }
    }

    @Override
    public Optional<StoredPost> find(String postId) {
        return Optional.ofNullable(posts.get(postId)).map(Entry::post);
    }

    @Override
    public boolean insert(StoredPost post) {
        return posts.putIfAbsent(post.postId(), new Entry(post, sequence.incrementAndGet())) == null;
    }

    @Override
    public boolean replace(StoredPost post, long expectedVersion) {
        boolean[] replaced = { false };
        posts.computeIfPresent(post.postId(), (id, current) -> {
            if (current.post().version() != expectedVersion) {
                return current;
            }
            replaced[0] = true;
            return new Entry(post.withVersion(expectedVersion + 1), sequence.incrementAndGet());
        });
        return replaced[0];
    }

    @Override
    public Optional<String> findPostIdByRef(Network network, String ref) {
        return posts.values().stream()
                .map(Entry::post)
                .filter(p -> {
                    NetworkState state = p.networks().get(network);
                    return state != null && state.refs().containsValue(ref);
                })
                .map(StoredPost::postId)
                .findFirst();
    }

    @Override
    public List<StoredPost> recent(int limit) {
        return posts.values().stream()
                .sorted(Comparator.comparingLong(Entry::seq).reversed())
                .limit(Math.max(0, limit))
                .map(Entry::post)
                .toList();
    }
}
