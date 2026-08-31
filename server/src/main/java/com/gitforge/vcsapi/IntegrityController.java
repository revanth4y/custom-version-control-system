package com.gitforge.vcsapi;

import com.gitforge.security.AuthenticatedUser;
import com.gitforge.user.User;
import com.gitforge.vcsapi.dto.IntegrityReport;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Verifying that a repository's stored objects still hash to their ids. */
@RestController
@RequestMapping("/api/v1/repositories/{owner}/{name}")
public class IntegrityController {

    private final IntegrityApiService integrity;

    public IntegrityController(IntegrityApiService integrity) {
        this.integrity = integrity;
    }

    /**
     * Repository-wide, and deliberately not scoped to a revision: the scan
     * covers what the store holds, including objects no branch reaches, so a
     * {@code ref} parameter would promise a narrowing this does not do.
     */
    @GetMapping("/integrity")
    public IntegrityReport integrity(
            @PathVariable String owner,
            @PathVariable String name,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return integrity.verify(owner, name, viewerOf(principal));
    }

    /** Null for anonymous callers, which the service layer reads as public access only. */
    private static User viewerOf(AuthenticatedUser principal) {
        return principal == null ? null : principal.user();
    }
}
