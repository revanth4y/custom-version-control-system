package com.gitforge.vcsapi;

import com.gitforge.security.AuthenticatedUser;
import com.gitforge.user.User;
import com.gitforge.vcsapi.dto.BlobResponse;
import com.gitforge.vcsapi.dto.CommitSummaryResponse;
import com.gitforge.vcsapi.dto.DirectoryResponse;
import com.gitforge.vcsapi.dto.PutContentRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Browsing directories, reading files, and writing a single file. */
@RestController
@RequestMapping("/api/v1/repositories/{owner}/{name}")
public class ContentController {

    private final ContentApiService contents;

    public ContentController(ContentApiService contents) {
        this.contents = contents;
    }

    @GetMapping("/tree")
    public DirectoryResponse tree(
            @PathVariable String owner,
            @PathVariable String name,
            @RequestParam(required = false) String ref,
            @RequestParam(required = false) String path,
            @RequestParam(required = false, defaultValue = "false") boolean withLastCommit,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return contents.listDirectory(owner, name, viewerOf(principal), ref, path, withLastCommit);
    }

    @GetMapping("/blob")
    public BlobResponse blob(
            @PathVariable String owner,
            @PathVariable String name,
            @RequestParam(required = false) String ref,
            @RequestParam String path,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return contents.readBlob(owner, name, viewerOf(principal), ref, path);
    }

    @PutMapping("/contents")
    public ResponseEntity<CommitSummaryResponse> putContent(
            @PathVariable String owner,
            @PathVariable String name,
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody PutContentRequest request) {

        CommitSummaryResponse commit = contents.putContent(owner, name, principal.user(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(commit);
    }

    /** Null for anonymous callers, which the service layer reads as public access only. */
    private static User viewerOf(AuthenticatedUser principal) {
        return principal == null ? null : principal.user();
    }
}
