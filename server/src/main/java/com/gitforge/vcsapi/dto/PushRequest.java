package com.gitforge.vcsapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Pushing one branch to a remote.
 *
 * <p>The token authenticates this server to the peer and is used for that call
 * only. It is never written to the repository: storing peer credentials is a
 * separate problem with its own requirements, and a token in a file is a token
 * nobody remembers is there.
 *
 * @param branch the single branch to push
 * @param token a bearer token the peer will accept
 */
public record PushRequest(
        @NotBlank @Size(max = 255) String branch,
        @NotBlank @Size(max = 4096) String token) {
}
