package com.gitforge.vcsapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Registering, or re-pointing, a remote.
 *
 * <p>The real rules live in {@code RemoteName} and {@code RemoteUrl}; these
 * annotations only stop an obviously malformed body reaching them, which is how
 * every other request in this API is shaped.
 */
public record CreateRemoteRequest(
        @NotBlank @Size(max = 64) String name,
        @NotBlank @Size(max = 2048) String url) {
}
