package com.gitforge.repo;

import com.gitforge.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A version-controlled repository.
 *
 * <p>Named {@code Repo} rather than {@code Repository} to avoid constant
 * ambiguity with Spring Data's {@code Repository} types.
 *
 * <p>This entity holds metadata only. Repository <em>contents</em> live in the
 * content-addressed object store and are referenced through branch pointers
 * added in a later phase — never duplicated into this table.
 */
@Entity
@Table(name = "repos")
@EntityListeners(AuditingEntityListener.class)
public class Repo {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RepoVisibility visibility;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Repo() {
        // required by JPA
    }

    public Repo(User owner, String name, String description, RepoVisibility visibility) {
        this.owner = owner;
        this.name = name;
        this.description = description;
        this.visibility = visibility;
    }

    /** Whether {@code viewer} may read this repository. A null viewer is anonymous. */
    public boolean isReadableBy(User viewer) {
        return visibility == RepoVisibility.PUBLIC || isOwnedBy(viewer);
    }

    /** Whether {@code viewer} may modify this repository. A null viewer is anonymous. */
    public boolean isOwnedBy(User viewer) {
        return viewer != null && owner.getId().equals(viewer.getId());
    }

    public UUID getId() {
        return id;
    }

    public User getOwner() {
        return owner;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public RepoVisibility getVisibility() {
        return visibility;
    }

    public void setVisibility(RepoVisibility visibility) {
        this.visibility = visibility;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Repo repo && id != null && id.equals(repo.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
