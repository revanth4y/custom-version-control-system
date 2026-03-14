package com.gitforge.issue;

import com.gitforge.common.error.ForbiddenException;
import com.gitforge.common.error.NotFoundException;
import com.gitforge.issue.dto.CommentResponse;
import com.gitforge.issue.dto.CreateCommentRequest;
import com.gitforge.issue.dto.UpdateCommentRequest;
import com.gitforge.repo.Repo;
import com.gitforge.repo.RepoService;
import com.gitforge.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Discussion on issues.
 *
 * <p>Kept apart from {@link IssueService} so neither grows into a catch-all for
 * everything issue-shaped: that one owns the issue lifecycle, this one owns the
 * conversation attached to it.
 *
 * <p>Access follows the repository, not the comment: anyone who can read the
 * repository can read its discussion, and any authenticated reader may join it.
 * Editing is narrower — the comment's author, or the repository owner acting as
 * moderator.
 */
@Service
public class IssueCommentService {

    private final IssueCommentRepository comments;
    private final IssueRepository issues;
    private final RepoService repoService;

    public IssueCommentService(
            IssueCommentRepository comments, IssueRepository issues, RepoService repoService) {
        this.comments = comments;
        this.issues = issues;
        this.repoService = repoService;
    }

    @Transactional(readOnly = true)
    public List<CommentResponse> list(String ownerUsername, String repoName, int number, User viewer) {
        Issue issue = requireIssue(ownerUsername, repoName, number, viewer);

        return comments.findByIssueOrderByCreatedAtAsc(issue).stream()
                .map(CommentResponse::from)
                .toList();
    }

    @Transactional
    public CommentResponse create(
            String ownerUsername, String repoName, int number, User author, CreateCommentRequest request) {

        Issue issue = requireIssue(ownerUsername, repoName, number, author);
        IssueComment comment = new IssueComment(issue, author, request.body());

        return CommentResponse.from(comments.save(comment));
    }

    @Transactional
    public CommentResponse update(UUID commentId, User viewer, UpdateCommentRequest request) {
        IssueComment comment = requireEditable(commentId, viewer);
        comment.setBody(request.body());

        return CommentResponse.from(comment);
    }

    @Transactional
    public void delete(UUID commentId, User viewer) {
        comments.delete(requireEditable(commentId, viewer));
    }

    /** Loads an issue in a repository the viewer may read. */
    private Issue requireIssue(String ownerUsername, String repoName, int number, User viewer) {
        Repo repo = repoService.requireReadable(ownerUsername, repoName, viewer);

        return issues.findByRepoAndNumber(repo, number)
                .orElseThrow(() -> new NotFoundException("Issue not found"));
    }

    private IssueComment requireEditable(UUID commentId, User viewer) {
        IssueComment comment = comments.findById(commentId)
                .orElseThrow(() -> new NotFoundException("Comment not found"));

        // Hide the existence of comments in unreadable repositories before
        // reporting a permission problem on readable ones.
        if (!comment.getIssue().getRepo().isReadableBy(viewer)) {
            throw new NotFoundException("Comment not found");
        }
        if (!comment.isEditableBy(viewer)) {
            throw new ForbiddenException("Only the comment author or the repository owner may modify it");
        }
        return comment;
    }
}
