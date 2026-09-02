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

    /**
     * When this issue was closed, or null while it is open.
     *
     * <p>Not derivable from {@code updatedAt}, which moves on any edit: an issue
     * closed in January and retitled in June has an updated_at of June. Null for
     * issues closed before this was recorded — those are closed but undated, and
     * inventing a date for them would be worse than admitting the gap.
     */
    @Column(name = "closed_at")
    private Instant closedAt;

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

    /**
     * Moves the issue between open and closed, recording when closure happened.
     *
     * <p>Only a real transition touches the timestamp. Closing an already-closed
     * issue leaves the original moment alone rather than resetting it to now —
     * an edit is not a re-closure — and it also leaves a historical NULL as NULL
     * rather than quietly filling it with the time somebody happened to save an
     * unrelated change.
     *
     * <p>Reopening clears it. A reopened issue is not closed, and keeping the old
     * date would leave a row claiming a closure that no longer holds.
     *
     * <p>The same shape {@code Release.setDraft} uses for its publication stamp,
     * and here for the same reason: putting it in the entity means every caller
     * gets it right, including ones that do not exist yet.
     */
    public void setStatus(IssueStatus status) {
        if (this.status == IssueStatus.OPEN && status == IssueStatus.CLOSED) {
            this.closedAt = Instant.now();
        } else if (this.status == IssueStatus.CLOSED && status == IssueStatus.OPEN) {
            this.closedAt = null;
        }
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** When the issue was closed, or empty while open or when it was never recorded. */
    public Instant getClosedAt() {
        return closedAt;
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
