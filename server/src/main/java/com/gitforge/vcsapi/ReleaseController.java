package com.gitforge.vcsapi;

import com.gitforge.common.error.BadRequestException;
import com.gitforge.release.ReleaseService;
import com.gitforge.security.AuthenticatedUser;
import com.gitforge.user.User;
import com.gitforge.vcsapi.dto.CreateReleaseRequest;
import com.gitforge.vcsapi.dto.ReleaseResponse;
import com.gitforge.vcsapi.dto.UpdateReleaseRequest;
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
 * Release endpoints.
 *
 * <p>A release is identified by its own id rather than by its tag, so the path
 * is a plain segment and no encoding question arises — unlike tags, whose names
 * may contain slashes and therefore travel as request parameters.
 *
 * <p>Reads are anonymous where repository visibility permits, with drafts hidden
 * from everyone but the owner, and every write requires authentication. That is
 * what the existing security rules already provide, so none of them change.
 */
@RestController
@RequestMapping("/api/v1/repositories/{owner}/{name}")
public class ReleaseController {

    private final ReleaseService releases;

    public ReleaseController(ReleaseService releases) {
        this.releases = releases;
    }

    @GetMapping("/releases")
    public List<ReleaseResponse> list(
            @PathVariable String owner,
            @PathVariable String name,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return releases.list(owner, name, viewerOf(principal)).stream()
                .map(ReleaseResponse::from)
                .toList();
    }

    @GetMapping("/releases/{id}")
    public ReleaseResponse get(
            @PathVariable String owner,
            @PathVariable String name,
            @PathVariable String id,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return ReleaseResponse.from(
                releases.get(owner, name, viewerOf(principal), identifier(id)));
    }

    @PostMapping("/releases")
    public ResponseEntity<ReleaseResponse> create(
            @PathVariable String owner,
            @PathVariable String name,
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateReleaseRequest request) {

        ReleaseResponse created = ReleaseResponse.from(releases.create(
                owner,
                name,
                principal.user(),
                request.tag(),
                request.name(),
                request.body(),
                request.draft(),
                request.prerelease()));

        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/releases/{id}")
    public ReleaseResponse update(
            @PathVariable String owner,
            @PathVariable String name,
            @PathVariable String id,
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody UpdateReleaseRequest request) {

        return ReleaseResponse.from(releases.update(
                owner,
                name,
                principal.user(),
                identifier(id),
                request.name(),
                request.body(),
                request.draft(),
                request.prerelease()));
    }

    @DeleteMapping("/releases/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable String owner,
            @PathVariable String name,
            @PathVariable String id,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        releases.delete(owner, name, principal.user(), identifier(id));
        return ResponseEntity.noContent().build();
    }

    /** A malformed id is a bad request, not a server error from the parser. */
    private static UUID identifier(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Malformed release id: " + id);
        }
    }

    /** Null for anonymous callers, which the service layer reads as public access only. */
    private static User viewerOf(AuthenticatedUser principal) {
        return principal == null ? null : principal.user();
    }
}
