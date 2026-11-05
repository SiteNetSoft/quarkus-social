package org.sitenetsoft.quarkus.social.hibernate;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.Length;

/**
 * One stored post ({@code social_post}). The {@code snapshot} is the {@code Post} as JSON; {@code version} is the
 * compare-and-set counter of {@code SocialPostStore.replace}, kept by Hibernate's optimistic locking.
 */
@Entity
@Table(name = "social_post", indexes = @Index(name = "social_post_updated_idx", columnList = "updated_at"))
public class SocialPostEntity {

    @Id
    @Column(name = "post_id", length = 256)
    private String postId;

    @Column(name = "account", nullable = false, length = 255)
    private String account;

    @Column(name = "kind", nullable = false, length = 32)
    private String kind;

    @Column(name = "visibility", nullable = false, length = 32)
    private String visibility;

    @Column(name = "snapshot", nullable = false, length = Length.LONG32)
    private String snapshot;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public String getPostId() {
        return postId;
    }

    public void setPostId(String postId) {
        this.postId = postId;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public String getSnapshot() {
        return snapshot;
    }

    public void setSnapshot(String snapshot) {
        this.snapshot = snapshot;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
