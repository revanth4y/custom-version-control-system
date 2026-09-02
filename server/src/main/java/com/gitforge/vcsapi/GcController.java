package com.gitforge.vcsapi;

import com.gitforge.security.AuthenticatedUser;
import com.gitforge.user.User;
import com.gitforge.vcsapi.dto.GcResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Finding and reclaiming objects no reference reaches. */
@RestController
@RequestMapping("/api/v1/repositories/{owner}/{name}")
public class GcController {

    private final GcApiService gc;

    public GcController(GcApiService gc) {
        this.gc = gc;
    }

    /**
     * What a collection would remove.
     *
     * <p>A GET because it changes nothing, which is the point: the destructive
     * operation is a different verb on the same path, so neither can be reached by
     * accident while meaning the other.
     */
    @GetMapping("/gc")
    public GcResponse report(
            @PathVariable String owner,
            @PathVariable String name,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return gc.report(owner, name, viewerOf(principal));
    }

    /**
     * Removes every object no reference reaches.
     *
     * <p>Owner-only, and explicitly asked for. Nothing else in the application
     * triggers collection — not deleting a branch, not committing, not merging,
     * not opening a repository and not starting up — because storage being
     * reclaimed as a side effect of an unrelated action is how history goes
     * missing without anyone deciding it should.
     */
    @PostMapping("/gc")
    public GcResponse collect(
            @PathVariable String owner,
            @PathVariable String name,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return gc.collect(owner, name, viewerOf(principal));
    }

    /** Null for anonymous callers, which the service layer reads as public access only. */
    private static User viewerOf(AuthenticatedUser principal) {
        return principal == null ? null : principal.user();
    }
}
