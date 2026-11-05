package org.sitenetsoft.quarkus.social.hibernate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;

import org.sitenetsoft.quarkus.social.Kind;
import org.sitenetsoft.quarkus.social.Network;
import org.sitenetsoft.quarkus.social.Visibility;
import org.sitenetsoft.quarkus.social.store.DeliveryProgress;
import org.sitenetsoft.quarkus.social.store.NetworkState;
import org.sitenetsoft.quarkus.social.store.NetworkState.Status;
import org.sitenetsoft.quarkus.social.store.SocialPostStore;
import org.sitenetsoft.quarkus.social.store.StoredPost;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.narayana.jta.QuarkusTransaction;

/**
 * {@link SocialPostStore} on Hibernate ORM (default persistence unit).
 *
 * <p>
 * <b>Transactions.</b> Every call runs in its own new transaction ({@code requiringNew}), whether or not the caller has
 * one. The rows record what was already sent to other networks, so a rollback of the application's transaction must
 * not erase them; and a lost compare-and-set or a duplicate insert, which end a transaction in error, must not mark the
 * caller's transaction rollback-only. The engine calls from a worker thread without a transaction, where this is the
 * same as joining.
 *
 * <p>
 * <b>Versions.</b> A new row starts at the {@code version} of the inserted {@code StoredPost}; every {@code replace} stores {@code expectedVersion + 1}.
 *
 * <p>
 * <b>Compare-and-set.</b> {@code replace} loads the row, compares the version, updates the row (Hibernate adds
 * {@code WHERE version = ?} and raises {@code OptimisticLockException} when another transaction got there first) and only
 * then rewrites the child rows, so two racing writers are decided on the post row, before they touch the same child
 * keys.
 */
@ApplicationScoped
public class HibernateSocialPostStore implements SocialPostStore {

    private static final TypeReference<Map<String, String>> REFS = new TypeReference<>() {
    };

    /** Upper bound of {@link #recent}; keeps the state lookup's IN list within database limits. */
    static final int MAX_RECENT = 1000;

    @Inject
    EntityManager em;

    @Inject
    ObjectMapper mapper;

    /** Thrown inside the transaction to roll it back when another writer won the compare-and-set. */
    private static final class LostRace extends RuntimeException {
        private static final long serialVersionUID = 1L;

        LostRace() {
            super(null, null, false, false);
        }
    }

    private static <T> T inTransaction(Supplier<T> work) {
        return QuarkusTransaction.requiringNew().call(work::get);
    }

    @Override
    public Optional<StoredPost> find(String postId) {
        return inTransaction(() -> Optional.ofNullable(em.find(SocialPostEntity.class, postId))
                .map(post -> toStored(post, statesOf(List.of(postId)).getOrDefault(postId, List.of()))));
    }

    @Override
    public boolean insert(StoredPost post) {
        try {
            return inTransaction(() -> {
                if (em.find(SocialPostEntity.class, post.postId()) != null) {
                    return false;
                }
                Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
                SocialPostEntity entity = new SocialPostEntity();
                entity.setPostId(post.postId());
                entity.setVersion(post.version());
                entity.setCreatedAt(now);
                fill(entity, post, now);
                em.persist(entity);
                writeChildren(post);
                em.flush();
                return true;
            });
        } catch (PersistenceException e) {
            // a concurrent insert of the same id fails on the primary key; any other violation is a real error
            if (inTransaction(() -> em.find(SocialPostEntity.class, post.postId()) != null)) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public boolean replace(StoredPost post, long expectedVersion) {
        try {
            return inTransaction(() -> {
                SocialPostEntity entity = em.find(SocialPostEntity.class, post.postId());
                if (entity == null || entity.getVersion() != expectedVersion) {
                    return false;
                }
                // updated_at always moves forward, so the row is always dirty and Hibernate always bumps the version
                Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
                fill(entity, post, now.isAfter(entity.getUpdatedAt()) ? now : entity.getUpdatedAt().plus(1, ChronoUnit.MICROS));
                try {
                    em.flush(); // UPDATE ... WHERE version = expected: the compare-and-set
                } catch (OptimisticLockException e) {
                    throw new LostRace();
                }
                deleteChildren(post.postId());
                writeChildren(post);
                em.flush();
                return true;
            });
        } catch (LostRace e) {
            return false;
        }
    }

    @Override
    public Optional<String> findPostIdByRef(Network network, String ref) {
        return inTransaction(() -> em
                .createQuery("select r.postId from SocialRefEntity r where r.network = :network "
                        + "and r.refHash = :hash and r.ref = :ref order by r.id", String.class)
                .setParameter("network", network.id()).setParameter("hash", sha256(ref)).setParameter("ref", ref)
                .setMaxResults(1).getResultList().stream().findFirst());
    }

    @Override
    public List<StoredPost> recent(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        int max = Math.min(limit, MAX_RECENT);
        return inTransaction(() -> {
            List<SocialPostEntity> posts = em
                    .createQuery("select p from SocialPostEntity p order by p.updatedAt desc, p.postId desc",
                            SocialPostEntity.class)
                    .setMaxResults(max).getResultList();
            Map<String, List<SocialNetworkStateEntity>> states = statesOf(posts.stream().map(SocialPostEntity::getPostId).toList());
            return posts.stream().map(p -> toStored(p, states.getOrDefault(p.getPostId(), List.of()))).toList();
        });
    }

    private void fill(SocialPostEntity entity, StoredPost post, Instant now) {
        entity.setAccount(post.account());
        entity.setKind(post.kind().name());
        entity.setVisibility(post.visibility().name());
        entity.setSnapshot(write(PostSnapshot.of(post.snapshot())));
        entity.setUpdatedAt(now);
    }

    private void deleteChildren(String postId) {
        em.createQuery("delete from SocialNetworkStateEntity s where s.postId = :id").setParameter("id", postId)
                .executeUpdate();
        em.createQuery("delete from SocialRefEntity r where r.postId = :id").setParameter("id", postId).executeUpdate();
    }

    private void writeChildren(StoredPost post) {
        for (NetworkState state : post.networks().values()) {
            SocialNetworkStateEntity row = new SocialNetworkStateEntity();
            row.setPostId(post.postId());
            row.setNetwork(state.network().id());
            row.setStatus(state.status().name());
            row.setRefs(write(new java.util.TreeMap<>(state.refs())));
            row.setCode(state.code().orElse(null));
            row.setDetail(state.detail().orElse(null));
            row.setAt(state.at());
            state.delivery().ifPresent(d -> {
                row.setDelivered(d.delivered());
                row.setFailed(d.failed());
                row.setPending(d.pending());
            });
            em.persist(row);
            Set<String> distinct = new LinkedHashSet<>();
            state.refs().forEach((name, value) -> {
                if (!NetworkState.URL_REF.equals(name)) {
                    distinct.add(value);
                }
            });
            for (String ref : distinct) {
                SocialRefEntity refRow = new SocialRefEntity();
                refRow.setNetwork(row.getNetwork());
                refRow.setRef(ref);
                refRow.setRefHash(sha256(ref));
                refRow.setPostId(post.postId());
                em.persist(refRow);
            }
        }
    }

    private Map<String, List<SocialNetworkStateEntity>> statesOf(List<String> postIds) {
        Map<String, List<SocialNetworkStateEntity>> byPost = new HashMap<>();
        em.createQuery("select s from SocialNetworkStateEntity s where s.postId in :ids", SocialNetworkStateEntity.class)
                .setParameter("ids", postIds).getResultList()
                .forEach(s -> byPost.computeIfAbsent(s.getPostId(), k -> new ArrayList<>()).add(s));
        return byPost;
    }

    private StoredPost toStored(SocialPostEntity entity, List<SocialNetworkStateEntity> rows) {
        Map<Network, NetworkState> networks = new HashMap<>();
        for (SocialNetworkStateEntity row : rows) {
            Network network = new Network(row.getNetwork());
            Optional<DeliveryProgress> delivery = row.getDelivered() == null ? Optional.empty()
                    : Optional.of(new DeliveryProgress(row.getDelivered(), row.getFailed(), row.getPending()));
            networks.put(network, new NetworkState(network, Status.valueOf(row.getStatus()), read(row.getRefs(), REFS),
                    Optional.ofNullable(row.getCode()), Optional.ofNullable(row.getDetail()), row.getAt(), delivery));
        }
        return new StoredPost(entity.getPostId(), entity.getAccount(), Kind.valueOf(entity.getKind()),
                Visibility.valueOf(entity.getVisibility()), read(entity.getSnapshot(), new TypeReference<PostSnapshot>() {
                }).toPost(), networks, entity.getVersion());
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise " + value.getClass().getSimpleName(), e);
        }
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot read stored JSON", e);
        }
    }
}
