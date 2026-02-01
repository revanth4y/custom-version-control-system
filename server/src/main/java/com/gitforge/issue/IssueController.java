package com.gitforge.issue;

import com.gitforge.issue.dto.CreateIssueRequest;
import com.gitforge.issue.dto.IssueResponse;
import com.gitforge.issue.dto.UpdateIssueRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class IssueController {

    private final IssueService issueService;

    public IssueController(IssueService issueService) {
        this.issueService = issueService;
    }

    @PostMapping("/repositories/{owner}/{name}/issues")
    public ResponseEntity<IssueResponse> create(
            @PathVariable String owner,
            @PathVariable String name,
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateIssueRequest request) {

        IssueResponse created = issueService.create(owner, name, principal.user(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/repositories/{owner}/{name}/issues")
    public List<IssueResponse> list(
            @PathVariable String owner,
            @PathVariable String name,
            @RequestParam(required = false) IssueStatus status,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return issueService.list(owner, name, viewerOf(principal), status);
    }

    @GetMapping("/repositories/{owner}/{name}/issues/{number}")
    public IssueResponse get(
            @PathVariable String owner,
            @PathVariable String name,
            @PathVariable int number,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return issueService.get(owner, name, number, viewerOf(principal));
    }

    @PatchMapping("/issues/{id}")
    public IssueResponse update(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody UpdateIssueRequest request) {

        return issueService.update(id, principal.user(), request);
    }

    @DeleteMapping("/issues/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        issueService.delete(id, principal.user());
        return ResponseEntity.noContent().build();
    }

    /** Null for anonymous callers, which the service layer treats as "public access only". */
    private User viewerOf(AuthenticatedUser principal) {
        return principal == null ? null : principal.user();
    }
}
