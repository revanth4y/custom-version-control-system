package com.gitforge.vcsapi.dto;

import jakarta.validation.constraints.Size;

/**
 * Metadata only. Every field is optional and null means "leave this alone".
 *
 * <p>There is deliberately no tag field. A release cannot be re-pointed: a
 * published note that quietly came to describe different code would be the same
 * failure immutable tags exist to prevent.
 */
public record UpdateReleaseRequest(
        @Size(max = 255) String name,
        @Size(max = 100_000) String body,
        Boolean draft,
        Boolean prerelease) {
}
