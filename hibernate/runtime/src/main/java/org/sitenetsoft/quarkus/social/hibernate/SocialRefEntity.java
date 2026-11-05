package org.sitenetsoft.quarkus.social.hibernate;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** One protocol id on one network and the post it belongs to ({@code social_ref}); answers {@code findPostIdByRef}. */
@Entity
@Table(name = "social_ref", indexes = @Index(name = "social_ref_post_idx", columnList = "post_id"))
@IdClass(SocialRefEntity.Key.class)
public class SocialRefEntity {

    @Id
    @Column(name = "network", length = 64)
    private String network;

    @Id
    @Column(name = "ref", length = 1024)
    private String ref;

    @Column(name = "post_id", nullable = false, length = 256)
    private String postId;

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

    public String getPostId() {
        return postId;
    }

    public void setPostId(String postId) {
        this.postId = postId;
    }

    /** The primary key {@code (network, ref)}. */
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        public String network;
        public String ref;

        public Key() {
        }

        public Key(String network, String ref) {
            this.network = network;
            this.ref = ref;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(network, k.network) && Objects.equals(ref, k.ref);
        }

        @Override
        public int hashCode() {
            return Objects.hash(network, ref);
        }
    }
}
