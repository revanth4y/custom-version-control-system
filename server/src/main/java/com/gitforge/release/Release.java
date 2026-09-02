package com.gitforge.release;

import com.gitforge.repo.Repo;
import com.gitforge.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
 * A published note attached to a tag.
 *
 * <p><strong>The association is the tag's name, not an object id.</strong> That
 * is the one decision worth stating plainly: object ids live in the filesystem
 * store and nowhere else, and a database column holding one would be a reference
 * the garbage collector cannot see — a root it does not know to read. Storing a
 * name costs a lookup when the release is shown and keeps the collector's
 * completeness argument true.
 *
 * <p>The tag itself is never owned by the release. Deleting a release removes
 * this row and nothing else; deleting a tag a release names is refused, so the
 * two can never be silently separated.
 *
 * <p>{@code publishedAt} is null exactly while the release is a draft, which is
 * why draft is not derived from it: a release can be published and later have
 * its metadata edited, and the moment it went out should not move when its notes
 * are corrected.
 */
@Entity
@Table(name = "releases")
@EntityListeners(AuditingEntityListener.class)
public class Release {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repo_id", nullable = false)
    private Repo repo;

    /** Null once the author's account has been deleted. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id")
    private User author;

    @Column(name = "tag_name", nullable = false, length = 255)
    private String tagName;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(nullable = false)
    private boolean draft;

    @Column(nullable = false)
    private boolean prerelease;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** When it stopped being a draft. Null while it still is one. */
    @Column(name = "published_at")
    private Instant publishedAt;

    protected Release() {
    }

    public Release(Repo repo, User author, String tagName, String name, String body,
                   boolean draft, boolean prerelease) {
        this.repo = repo;
        this.author = author;
        this.tagName = tagName;
        this.name = name;
        this.body = body;
        this.draft = draft;
        this.prerelease = prerelease;
        this.publishedAt = draft ? null : Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Repo getRepo() {
        return repo;
    }

    public User getAuthor() {
        return author;
    }

    public String getTagName() {
        return tagName;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public boolean isDraft() {
        return draft;
    }

    /**
     * Publishing stamps the moment it went out; a published release stays
     * published-at whatever time it first was, even if it is edited afterwards.
     *
     * <p>Returning to draft deliberately clears the stamp rather than keeping it:
     * a draft that claims a publication date is a draft nobody can trust.
     */
    public void setDraft(boolean draft) {
        if (this.draft && !draft) {
            this.publishedAt = Instant.now();
        } else if (!this.draft && draft) {
            this.publishedAt = null;
        }
        this.draft = draft;
    }

    public boolean isPrerelease() {
        return prerelease;
    }

    public void setPrerelease(boolean prerelease) {
        this.prerelease = prerelease;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Release release && id != null && id.equals(release.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
