package com.gitforge.repo;

import com.gitforge.common.PageResponse;
import com.gitforge.repo.dto.CreateRepoRequest;
import com.gitforge.repo.dto.RepoResponse;
import com.gitforge.repo.dto.UpdateRepoRequest;
import com.gitforge.security.AuthenticatedUser;
import com.gitforge.user.User;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
public class RepoController {

    private static final int MAX_PAGE_SIZE = 100;

    private final RepoService repoService;

    public RepoController(RepoService repoService) {
        this.repoService = repoService;
    }

    @PostMapping("/repositories")
    public ResponseEntity<RepoResponse> create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateRepoRequest request) {

        RepoResponse created = repoService.create(principal.user(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** Public discovery listing; private repositories are never included. */
    @GetMapping("/repositories")
    public PageResponse<RepoResponse> listPublic(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        int boundedSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        var pageable = PageRequest.of(Math.max(page, 0), boundedSize, Sort.by(Sort.Direction.DESC, "updatedAt"));

        return PageResponse.from(repoService.listPublic(pageable));
    }

    @GetMapping("/repositories/{owner}/{name}")
    public RepoResponse get(
            @PathVariable String owner,
            @PathVariable String name,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return repoService.get(owner, name, viewerOf(principal));
    }

    @GetMapping("/users/{username}/repositories")
    public List<RepoResponse> listByOwner(
            @PathVariable String username,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return repoService.listByOwner(username, viewerOf(principal));
    }

    @PatchMapping("/repositories/{id}")
    public RepoResponse update(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody UpdateRepoRequest request) {

        return repoService.update(id, principal.user(), request);
    }

    @DeleteMapping("/repositories/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        repoService.delete(id, principal.user());
        return ResponseEntity.noContent().build();
    }

    /** Null for anonymous callers, which the service layer treats as "public access only". */
    private User viewerOf(AuthenticatedUser principal) {
        return principal == null ? null : principal.user();
    }
}
