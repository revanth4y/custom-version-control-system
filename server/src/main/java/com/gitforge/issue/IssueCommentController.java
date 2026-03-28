package com.gitforge.issue;

import com.gitforge.issue.dto.CommentResponse;
import com.gitforge.issue.dto.CreateCommentRequest;
import com.gitforge.issue.dto.UpdateCommentRequest;
import com.gitforge.security.AuthenticatedUser;
import com.gitforge.user.User;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Issue discussion.
 *
 * <p>Listing and creating are nested under the repository so authorization
 * follows the same path as every other repository read; editing and deleting
 * address a comment by id, matching the existing issue endpoints.
 */
@RestController
@RequestMapping("/api/v1")
public class IssueCommentController {

    private final IssueCommentService comments;

    public IssueCommentController(IssueCommentService comments) {
        this.comments = comments;
    }

    @GetMapping("/repositories/{owner}/{name}/issues/{number}/comments")
    public List<CommentResponse> list(
            @PathVariable String owner,
            @PathVariable String name,
            @PathVariable int number,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return comments.list(owner, name, number, viewerOf(principal));
    }

    @PostMapping("/repositories/{owner}/{name}/issues/{number}/comments")
    public ResponseEntity<CommentResponse> create(
            @PathVariable String owner,
            @PathVariable String name,
            @PathVariable int number,
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateCommentRequest request) {

        CommentResponse created = comments.create(owner, name, number, principal.user(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/issue-comments/{id}")
    public CommentResponse update(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody UpdateCommentRequest request) {

        return comments.update(id, principal.user(), request);
    }

    @DeleteMapping("/issue-comments/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        comments.delete(id, principal.user());
        return ResponseEntity.noContent().build();
    }

    /** Null for anonymous callers, which the service layer reads as public access only. */
    private static User viewerOf(AuthenticatedUser principal) {
        return principal == null ? null : principal.user();
    }
}
