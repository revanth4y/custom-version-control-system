package com.gitforge.release;

import com.gitforge.common.error.BadRequestException;
import com.gitforge.common.error.ConflictException;
import com.gitforge.common.error.NotFoundException;
import com.gitforge.repo.Repo;
import com.gitforge.repo.RepoRepository;
import com.gitforge.repo.RepoService;
import com.gitforge.user.User;
import com.gitforge.vcs.ref.RefException;
import com.gitforge.vcs.repository.VcsRepository;
import com.gitforge.vcsapi.VcsRepositoryProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Releases: creating them, editing what they say, and taking them down.
 *
 * <p>A release is application metadata about a point the version-control engine
 * already holds, so this class owns the metadata and defers everything about the
 * point itself. It never writes a ref, never writes an object, and never deletes
 * one — the strongest statement of that is {@link #delete}, which removes a row
 * and leaves the tag exactly where it was.
 *
 * <p><strong>Draft visibility is an authorization rule, not a filter.</strong> A
 * draft is invisible to everyone but the owner, and the invisibility is a 404
 * rather than a 403, matching how a private repository behaves: telling a
 * stranger that a draft exists is itself a disclosure.
 */
@Service
public class ReleaseService implements ReleaseGuard {

    /**
     * Bounded because a release body is rendered on a page and stored in a row.
     * Generous enough for long notes, and far below the 16 MiB the request filter
     * admits, so an oversized body is refused as a bad request rather than
     * absorbed as a large one.
     */
    public static final int MAX_BODY_LENGTH = 100_000;

    private final ReleaseRepository releases;
    private final RepoService repoService;
    private final RepoRepository repos;
    private final VcsRepositoryProvider repositories;

    public ReleaseService(
            ReleaseRepository releases,
            RepoService repoService,
            RepoRepository repos,
            VcsRepositoryProvider repositories) {

        this.releases = releases;
        this.repoService = repoService;
        this.repos = repos;
        this.repositories = repositories;
    }

    @Transactional(readOnly = true)
    public List<Release> list(String owner, String name, User viewer) {
        Repo repo = repoService.requireReadable(owner, name, viewer);

        return releases.findByRepoOrderByCreatedAtDesc(repo).stream()
                .filter(release -> isVisibleTo(release, repo, viewer))
                .toList();
    }

    @Transactional(readOnly = true)
    public Release get(String owner, String name, User viewer, UUID id) {
        Repo repo = repoService.requireReadable(owner, name, viewer);
        Release release = releases.findById(id)
                .filter(candidate -> candidate.getRepo().getId().equals(repo.getId()))
                .orElseThrow(() -> new NotFoundException("Release not found"));

        if (!isVisibleTo(release, repo, viewer)) {
            // Not "forbidden": a stranger should not learn that a draft exists.
            throw new NotFoundException("Release not found");
        }
        return release;
    }

    @Transactional
    public Release create(
            String owner,
            String name,
            User viewer,
            String tagName,
            String title,
            String body,
            boolean draft,
            boolean prerelease) {

        Repo repo = repoService.requireWritable(owner, name, viewer);

        requireTitle(title);
        requireBodyWithinLimit(body);
        requireExistingTag(owner, name, viewer, tagName);

        if (releases.existsByRepoAndTagName(repo, tagName)) {
            throw new ConflictException("A release already exists for tag " + tagName);
        }
        return releases.save(
                new Release(repo, viewer, tagName, title, body, draft, prerelease));
    }

    /**
     * Changes what a release says.
     *
     * <p>Deliberately cannot change which tag it names. Re-pointing a release
     * would let a published note quietly come to describe different code, which
     * is the same failure immutable tags exist to prevent; moving a release means
     * deleting it and creating another, as moving a tag does.
     *
     * @param title null to leave unchanged
     * @param body null to leave unchanged
     * @param draft null to leave unchanged
     * @param prerelease null to leave unchanged
     */
    @Transactional
    public Release update(
            String owner,
            String name,
            User viewer,
            UUID id,
            String title,
            String body,
            Boolean draft,
            Boolean prerelease) {

        Repo repo = repoService.requireWritable(owner, name, viewer);
        Release release = releases.findById(id)
                .filter(candidate -> candidate.getRepo().getId().equals(repo.getId()))
                .orElseThrow(() -> new NotFoundException("Release not found"));

        if (title != null) {
            requireTitle(title);
            release.setName(title);
        }
        if (body != null) {
            requireBodyWithinLimit(body);
            release.setBody(body);
        }
        if (draft != null) {
            release.setDraft(draft);
        }
        if (prerelease != null) {
            release.setPrerelease(prerelease);
        }
        return releases.save(release);
    }

    /**
     * Removes a release.
     *
     * <p>The row and nothing else. The tag it named stays, and so does everything
     * the tag protects — a release is a note about a point in history, and taking
     * down the note does not take down the history.
     */
    @Transactional
    public void delete(String owner, String name, User viewer, UUID id) {
        Repo repo = repoService.requireWritable(owner, name, viewer);
        Release release = releases.findById(id)
                .filter(candidate -> candidate.getRepo().getId().equals(repo.getId()))
                .orElseThrow(() -> new NotFoundException("Release not found"));

        releases.delete(release);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isReferenced(String owner, String repositoryName, String tagName) {
        // Read directly rather than through a visibility check: the caller has
        // already established it may write to this repository, and asking again
        // here would turn "is this tag spoken for" into a second authorization.
        return repos.findByOwnerUsernameAndName(owner, repositoryName)
                .map(repo -> releases.existsByRepoAndTagName(repo, tagName))
                .orElse(false);
    }

    /**
     * A draft is the owner's alone until it is published.
     *
     * <p>Ownership rather than write access, because there is no collaborator
     * model in this application and inventing one here would be a larger change
     * than releases.
     */
    private static boolean isVisibleTo(Release release, Repo repo, User viewer) {
        if (!release.isDraft()) {
            return true;
        }
        return viewer != null
                && repo.getOwner() != null
                && viewer.getId() != null
                && viewer.getId().equals(repo.getOwner().getId());
    }

    private void requireExistingTag(String owner, String name, User viewer, String tagName) {
        if (tagName == null || tagName.isBlank()) {
            throw new BadRequestException("A release must name a tag");
        }
        VcsRepository repository = repositories.forWrite(owner, name, viewer);
        boolean exists;
        try {
            exists = repository.tags().tagExists(tagName);
        } catch (RefException ex) {
            // A name no tag could have is a bad request, not a missing tag.
            throw new BadRequestException(ex.getMessage());
        }
        if (!exists) {
            throw new NotFoundException("Tag not found: " + tagName);
        }
    }

    private static void requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new BadRequestException("A release must have a title");
        }
        if (title.length() > 255) {
            throw new BadRequestException("A release title must be at most 255 characters");
        }
    }

    private static void requireBodyWithinLimit(String body) {
        if (body != null && body.length() > MAX_BODY_LENGTH) {
            throw new BadRequestException(
                    "A release body must be at most " + MAX_BODY_LENGTH + " characters");
        }
    }

    /** Exposed for the API layer, which needs the tag alongside the release. */
    public Optional<Release> findByTag(String owner, String name, User viewer, String tagName) {
        Repo repo = repoService.requireReadable(owner, name, viewer);
        return releases.findByRepoAndTagName(repo, tagName)
                .filter(release -> isVisibleTo(release, repo, viewer));
    }
}
