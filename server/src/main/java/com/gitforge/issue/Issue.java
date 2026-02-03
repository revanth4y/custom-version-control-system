package com.gitforge.issue;

import com.gitforge.repo.Repo;
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
 * An issue filed against a repository.
 *
 * <p>{@code number} is the per-repository sequence shown in the UI (#1, #2, ...),
 * assigned by {@link IssueService} under a lock on the owning repository so that
 * concurrent creation cannot produce duplicates.
 */
@Entity
@Table(name = "issues")
@EntityListeners(AuditingEntityListener.class)
public class Issue {

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

    @Column(nullable = false)
    private Integer number;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private IssueStatus status;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Issue() {
        // required by JPA
    }

    public Issue(Repo repo, User author, int number, String title, String body) {
        this.repo = repo;
        this.author = author;
        this.number = number;
        this.title = title;
        this.body = body;
        this.status = IssueStatus.OPEN;
    }

    /**
     * Issues may be edited by their author or by the repository owner, mirroring
     * the permissions GitHub grants.
     */
    public boolean isEditableBy(User viewer) {
        if (viewer == null) {
            return false;
        }
        boolean isAuthor = author != null && author.getId().equals(viewer.getId());
        return isAuthor || repo.isOwnedBy(viewer);
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

    public Integer getNumber() {
        return number;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public IssueStatus getStatus() {
        return status;
    }

    public void setStatus(IssueStatus status) {
        this.status = status;
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
        return other instanceof Issue issue && id != null && id.equals(issue.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
