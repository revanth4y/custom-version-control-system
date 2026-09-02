package com.gitforge.vcsapi.dto;

import com.gitforge.release.Release;

import java.time.Instant;

/**
 * A release, and the tag it names.
 *
 * <p>The tag is carried as a name because that is what the release actually
 * stores; {@code tag} is not resolved here, so a release whose tag has somehow
 * gone still describes itself rather than failing to render. The tag endpoints
 * are where a caller asks what that name currently points at.
 *
 * @param authorName null once the author's account has been deleted
 * @param publishedAt null while the release is still a draft
 */
public record ReleaseResponse(
        String id,
        String tag,
        String name,
        String body,
        boolean draft,
        boolean prerelease,
        String authorName,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt) {

    public static ReleaseResponse from(Release release) {
        return new ReleaseResponse(
                release.getId().toString(),
                release.getTagName(),
                release.getName(),
                release.getBody(),
                release.isDraft(),
                release.isPrerelease(),
                release.getAuthor() == null ? null : release.getAuthor().getUsername(),
                release.getCreatedAt(),
                release.getUpdatedAt(),
                release.getPublishedAt());
    }
}
