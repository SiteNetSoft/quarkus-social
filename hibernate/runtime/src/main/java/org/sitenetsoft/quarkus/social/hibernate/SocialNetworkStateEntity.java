package org.sitenetsoft.quarkus.social.hibernate;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import org.hibernate.Length;

/** What was written on one network for one post ({@code social_network_state}). */
@Entity
@Table(name = "social_network_state")
@IdClass(SocialNetworkStateEntity.Key.class)
public class SocialNetworkStateEntity {

    @Id
    @Column(name = "post_id", length = 256)
    private String postId;

    @Id
    @Column(name = "network", length = 64)
    private String network;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    /** The protocol ids, a JSON object of strings. */
    @Column(name = "refs", nullable = false, length = Length.LONG32)
    private String refs;

    @Column(name = "code", length = 64)
    private String code;

    @Column(name = "detail", length = Length.LONG32)
    private String detail;

    @Column(name = "at", nullable = false)
    private Instant at;

    /** The delivery counts; all three are null when the network reports no delivery progress. */
    @Column(name = "delivered")
    private Integer delivered;

    @Column(name = "failed")
    private Integer failed;

    @Column(name = "pending")
    private Integer pending;

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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRefs() {
        return refs;
    }

    public void setRefs(String refs) {
        this.refs = refs;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Instant getAt() {
        return at;
    }

    public void setAt(Instant at) {
        this.at = at;
    }

    public Integer getDelivered() {
        return delivered;
    }

    public void setDelivered(Integer delivered) {
        this.delivered = delivered;
    }

    public Integer getFailed() {
        return failed;
    }

    public void setFailed(Integer failed) {
        this.failed = failed;
    }

    public Integer getPending() {
        return pending;
    }

    public void setPending(Integer pending) {
        this.pending = pending;
    }

    /** The primary key {@code (postId, network)}. */
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        public String postId;
        public String network;

        public Key() {
        }

        public Key(String postId, String network) {
            this.postId = postId;
            this.network = network;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(postId, k.postId) && Objects.equals(network, k.network);
        }

        @Override
        public int hashCode() {
            return Objects.hash(postId, network);
        }
    }
}
