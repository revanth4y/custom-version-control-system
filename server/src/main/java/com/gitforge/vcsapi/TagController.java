package com.gitforge.vcsapi;

import com.gitforge.security.AuthenticatedUser;
import com.gitforge.user.User;
import com.gitforge.vcsapi.dto.CreateTagRequest;
import com.gitforge.vcsapi.dto.TagResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Tag endpoints.
 *
 * <p>Tag names travel as request parameters rather than path segments, for the
 * same reason branch names do: {@code release/v1.0} contains a slash and would
 * otherwise have to be encoded into a path variable.
 *
 * <p>Reads are GETs and writes are POSTs and DELETEs, which is what lets
 * {@code SecurityConfig} stay untouched — {@code GET /repositories/**} is already
 * anonymous with visibility enforced in the service layer, and
 * {@code anyRequest().authenticated()} already covers the rest.
 */
@RestController
@RequestMapping("/api/v1/repositories/{owner}/{name}")
public class TagController {

    private final TagApiService tags;

    public TagController(TagApiService tags) {
        this.tags = tags;
    }

    @GetMapping("/tags")
    public List<TagResponse> list(
            @PathVariable String owner,
            @PathVariable String name,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return tags.list(owner, name, viewerOf(principal));
    }

    @GetMapping("/tag")
    public TagResponse get(
            @PathVariable String owner,
            @PathVariable String name,
            @RequestParam("name") String tag,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return tags.get(owner, name, viewerOf(principal), tag);
    }

    @PostMapping("/tags")
    public ResponseEntity<TagResponse> create(
            @PathVariable String owner,
            @PathVariable String name,
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateTagRequest request) {

        TagResponse created = tags.create(owner, name, principal.user(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/tags")
    public ResponseEntity<Void> delete(
            @PathVariable String owner,
            @PathVariable String name,
            @RequestParam("name") String tag,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        tags.delete(owner, name, principal.user(), tag);
        return ResponseEntity.noContent().build();
    }

    /** Null for anonymous callers, which the service layer reads as public access only. */
    private static User viewerOf(AuthenticatedUser principal) {
        return principal == null ? null : principal.user();
    }
}
