package com.gitforge.vcsapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param tag the tag this release describes; it must already exist
 * @param name the release title
 * @param body the notes, optional
 */
public record CreateReleaseRequest(
        @NotBlank @Size(max = 255) String tag,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 100_000) String body,
        boolean draft,
        boolean prerelease) {
}
