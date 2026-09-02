package com.gitforge.vcsapi.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Objects arriving from elsewhere, and optionally a branch to move onto one.
 *
 * <p>The branch and commit are optional together. A push that does not fit in one
 * request sends its earlier batches with neither, and asks for the move only on
 * the last — so the move is decided once, against a store that already holds
 * everything.
 *
 * @param objects the objects, canonical payload base64-encoded
 * @param branch the branch to move afterwards, or null to store only
 * @param commit where it should point, or null when {@code branch} is
 */
public record ReceiveObjectsRequest(
        @Size(max = 500) List<RemoteObjectsResponse.ObjectEntry> objects,
        @Size(max = 255) String branch,
        @Size(max = 40) String commit) {
}
