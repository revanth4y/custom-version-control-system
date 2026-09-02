package com.gitforge.vcsapi;

import com.gitforge.security.AuthenticatedUser;
import com.gitforge.user.User;
import com.gitforge.vcsapi.dto.CreateRemoteRequest;
import com.gitforge.vcsapi.dto.FetchResponse;
import com.gitforge.vcsapi.dto.MissingObjectsResponse;
import com.gitforge.vcsapi.dto.PushRequest;
import com.gitforge.vcsapi.dto.PushResponse;
import com.gitforge.vcsapi.dto.ReceiveObjectsRequest;
import com.gitforge.vcsapi.dto.ReceiveObjectsResponse;
import com.gitforge.vcsapi.dto.RemoteObjectsResponse;
import com.gitforge.vcsapi.dto.RemoteRefsResponse;
import com.gitforge.vcsapi.dto.RemoteResponse;
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
 * Synchronising one repository with another.
 *
 * <p>Reads are GETs and writes are POSTs, which is not a stylistic choice here
 * but the thing that lets the security configuration stay exactly as it was:
 * {@code GET /api/v1/repositories/**} is already anonymous with visibility
 * enforced in the service layer, and {@code anyRequest().authenticated()} already
 * covers the rest. Object ids therefore travel in the query string, bounded by
 * {@code TransferLimits.MAX_IDS_PER_REQUEST} so a URL stays a reasonable length.
 */
@RestController
@RequestMapping("/api/v1/repositories/{owner}/{name}")
public class RemoteController {

    private final RemoteApiService remotes;

    public RemoteController(RemoteApiService remotes) {
        this.remotes = remotes;
    }

    // ---- What a peer may ask of us -------------------------------------------

    /** Every branch and its tip, for a peer deciding what to fetch. */
    @GetMapping("/remote-refs")
    public RemoteRefsResponse advertise(
            @PathVariable String owner,
            @PathVariable String name,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return remotes.advertise(owner, name, viewerOf(principal));
    }

    /** Which of the offered ids this repository does not hold. */
    @GetMapping("/remote-objects/missing")
    public MissingObjectsResponse missing(
            @PathVariable String owner,
            @PathVariable String name,
            @RequestParam(name = "id", required = false) List<String> ids,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return remotes.missing(owner, name, viewerOf(principal), ids);
    }

    /** The named objects, canonical payload base64-encoded. */
    @GetMapping("/remote-objects")
    public RemoteObjectsResponse objects(
            @PathVariable String owner,
            @PathVariable String name,
            @RequestParam(name = "id", required = false) List<String> ids,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return remotes.objects(owner, name, viewerOf(principal), ids);
    }

    /**
     * Accepts objects, and optionally a branch move, from a peer.
     *
     * <p>Owner-only, and the branch moves only after every object beneath it is
     * durable and the move is proven to be a fast-forward.
     */
    @PostMapping("/remote-objects/receive")
    public ReceiveObjectsResponse receive(
            @PathVariable String owner,
            @PathVariable String name,
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody ReceiveObjectsRequest request) {

        return remotes.receive(owner, name, principal.user(), request);
    }

    // ---- What we do on our own repository ------------------------------------

    /** The remotes this repository knows about. */
    @GetMapping("/remotes")
    public List<RemoteResponse> list(
            @PathVariable String owner,
            @PathVariable String name,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return remotes.list(owner, name, viewerOf(principal));
    }

    /** Registers a remote, or re-points one of the same name. Owner-only. */
    @PostMapping("/remotes")
    public ResponseEntity<RemoteResponse> register(
            @PathVariable String owner,
            @PathVariable String name,
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateRemoteRequest request) {

        RemoteResponse created = remotes.register(owner, name, principal.user(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** Forgets a remote. Its tracking refs and objects stay. Owner-only. */
    @DeleteMapping("/remotes")
    public ResponseEntity<Void> forget(
            @PathVariable String owner,
            @PathVariable String name,
            @RequestParam(name = "name") String remote,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        remotes.forget(owner, name, principal.user(), remote);
        return ResponseEntity.noContent().build();
    }

    /** Fetches from a remote into remote-tracking refs. Owner-only. */
    @PostMapping("/remotes/{remote}/fetch")
    public FetchResponse fetch(
            @PathVariable String owner,
            @PathVariable String name,
            @PathVariable String remote,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return remotes.fetch(owner, name, principal.user(), remote);
    }

    /** Pushes one branch to a remote, fast-forward only. Owner-only. */
    @PostMapping("/remotes/{remote}/push")
    public PushResponse push(
            @PathVariable String owner,
            @PathVariable String name,
            @PathVariable String remote,
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody PushRequest request) {

        return remotes.push(owner, name, principal.user(), remote, request);
    }

    /** Null for anonymous callers, which the service layer reads as public access only. */
    private static User viewerOf(AuthenticatedUser principal) {
        return principal == null ? null : principal.user();
    }
}
