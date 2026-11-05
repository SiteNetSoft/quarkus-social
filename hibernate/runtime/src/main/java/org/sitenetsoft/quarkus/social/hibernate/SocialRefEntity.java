package org.sitenetsoft.quarkus.social.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * One protocol id on one network and the post it belongs to ({@code social_ref}); answers {@code findPostIdByRef}.
 * Not unique: two posts may carry the same ref value. {@code ref_hash} (lowercase SHA-256 hex of {@code ref}) keeps
 * the lookup index short; the lookup compares {@code ref} exactly. Refs longer than
 * {@link org.sitenetsoft.quarkus.social.store.SocialPostStore#MAX_INDEXED_REF_LENGTH} (the column) get no row.
 */
@Entity
@Table(name = "social_ref", indexes = {
        @Index(name = "social_ref_lookup_idx", columnList = "network, ref_hash"),
        @Index(name = "social_ref_post_idx", columnList = "post_id") })
public class SocialRefEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "post_id", nullable = false, length = 256)
    private String postId;

    @Column(name = "network", nullable = false, length = 64)
    private String network;

    @Column(name = "ref", nullable = false, length = 1024)
    private String ref;

    @Column(name = "ref_hash", nullable = false, length = 64)
    private String refHash;

    public Long getId() {
        return id;
    }

    public String getPostId() {
        return postId;
    }

    public void setPostId(String postId) {
        this.postId = postId;
    }

    public String getNetwork() {
        return network;
    }

    public void setNetwork(String network) {
        this.network = network;
    }

    public String getRef() {
        return ref;
    }

    public void setRef(String ref) {
        this.ref = ref;
    }

    public String getRefHash() {
        return refHash;
    }

    public void setRefHash(String refHash) {
        this.refHash = refHash;
    }
}
