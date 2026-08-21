package com.gitforge.vcsapi;

import com.gitforge.security.AuthenticatedUser;
import com.gitforge.user.User;
import com.gitforge.vcsapi.dto.BranchResponse;
import com.gitforge.vcsapi.dto.CreateBranchRequest;
import com.gitforge.vcsapi.dto.HeadResponse;
import com.gitforge.vcsapi.dto.SetHeadRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Branch and HEAD endpoints.
 *
 * <p>Branch names travel as request parameters rather than path segments,
 * because a name like {@code feature/login} contains a slash and would otherwise
 * have to be encoded into a path variable.
 */
@RestController
@RequestMapping("/api/v1/repositories/{owner}/{name}")
public class BranchController {

    private final BranchApiService branches;

    public BranchController(BranchApiService branches) {
        this.branches = branches;
    }

    @GetMapping("/branches")
    public List<BranchResponse> list(
            @PathVariable String owner,
            @PathVariable String name,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return branches.list(owner, name, viewerOf(principal));
    }

    @PostMapping("/branches")
    public ResponseEntity<BranchResponse> create(
            @PathVariable String owner,
            @PathVariable String name,
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateBranchRequest request) {

        BranchResponse created = branches.create(owner, name, principal.user(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/branches")
    public ResponseEntity<Void> delete(
            @PathVariable String owner,
            @PathVariable String name,
            @RequestParam(name = "name") String branch,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        branches.delete(owner, name, principal.user(), branch);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/head")
    public HeadResponse head(
            @PathVariable String owner,
            @PathVariable String name,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return branches.head(owner, name, viewerOf(principal));
    }

    @PutMapping("/head")
    public HeadResponse setHead(
            @PathVariable String owner,
            @PathVariable String name,
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody SetHeadRequest request) {

        return branches.setHead(owner, name, principal.user(), request);
    }

    /** Null for anonymous callers, which the service layer reads as public access only. */
    private static User viewerOf(AuthenticatedUser principal) {
        return principal == null ? null : principal.user();
    }
}
