package com.gitforge.vcsapi;

import com.gitforge.security.AuthenticatedUser;
import com.gitforge.vcsapi.dto.MergeRequest;
import com.gitforge.vcsapi.dto.MergeResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Merging one branch into another. */
@RestController
@RequestMapping("/api/v1/repositories/{owner}/{name}")
public class MergeController {

    private static final String CONFLICTED = "CONFLICTED";

    private final MergeApiService merges;

    public MergeController(MergeApiService merges) {
        this.merges = merges;
    }

    /**
     * @return 200 for a merge that completed in any form, or 409 carrying the
     *     conflicting paths when the branches could not be reconciled
     */
    @PostMapping("/merge")
    public ResponseEntity<MergeResponse> merge(
            @PathVariable String owner,
            @PathVariable String name,
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody MergeRequest request) {

        MergeResponse response = merges.merge(owner, name, principal.user(), request);

        // A conflict is a real answer rather than a failure, but the request
        // could not be carried out in the repository's current state.
        HttpStatus status = CONFLICTED.equals(response.outcome()) ? HttpStatus.CONFLICT : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }
}
