package com.gitforge.vcsapi;

import com.gitforge.common.error.BadRequestException;
import com.gitforge.common.error.ConflictException;
import com.gitforge.common.error.NotFoundException;
import com.gitforge.release.ReleaseGuard;
import com.gitforge.user.User;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Signature;
import com.gitforge.vcs.object.Tag;
import com.gitforge.vcs.ref.RefException;
import com.gitforge.vcs.repository.VcsRepository;
import com.gitforge.vcsapi.dto.BranchTipResponse;
import com.gitforge.vcsapi.dto.CreateTagRequest;
import com.gitforge.vcsapi.dto.TagResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Tag operations for the API.
 *
 * <p>Sits between the controller and the engine, and is where a {@link RefException}
 * acquires an HTTP meaning. The engine raises one exception for several
 * situations — absent, duplicate, malformed, target missing — because they are
 * all "the reference is not in the state you assumed"; deciding which of those is
 * a 400, a 404 or a 409 is an HTTP concern and is made here.
 *
 * <p>One rule is enforced from outside the engine entirely: a tag a release
 * references cannot be deleted. The engine has no idea releases exist, and should
 * not — it stores refs and objects. Asking a {@link ReleaseGuard} keeps that
 * knowledge in the layer that has it.
 */
@Service
public class TagApiService {

    private final VcsRepositoryProvider repositories;
    private final ReleaseGuard releases;

    public TagApiService(VcsRepositoryProvider repositories, ReleaseGuard releases) {
        this.repositories = repositories;
        this.releases = releases;
    }

    public List<TagResponse> list(String owner, String name, User viewer) {
        VcsRepository repository = repositories.forRead(owner, name, viewer);

        return repository.tags().listTags().stream()
                .map(tag -> describe(repository, tag))
                .toList();
    }

    public TagResponse get(String owner, String name, User viewer, String tag) {
        VcsRepository repository = repositories.forRead(owner, name, viewer);

        requireName(tag);
        if (!existsQuietly(repository, tag)) {
            throw new NotFoundException("Tag not found: " + tag);
        }
        return describe(repository, tag);
    }

    public TagResponse create(String owner, String name, User viewer, CreateTagRequest request) {
        VcsRepository repository = repositories.forWrite(owner, name, viewer);

        // Checked quietly: a malformed name is not an existing tag, and must be
        // reported as a bad request rather than as a conflict.
        if (existsQuietly(repository, request.name())) {
            throw new ConflictException("Tag already exists: " + request.name());
        }
        ObjectId target = repository.branches().resolve(request.target())
                .orElseThrow(() -> new NotFoundException("Cannot resolve target: " + request.target()));

        try {
            if (isAnnotated(request)) {
                repository.tags().createAnnotated(
                        request.name(), target, signatureOf(viewer), request.message());
            } else {
                repository.tags().createLightweight(request.name(), target);
            }
        } catch (RefException ex) {
            // Whatever remains is a malformed name: existence and the target were
            // both settled above.
            throw new BadRequestException(ex.getMessage());
        }
        return describe(repository, request.name());
    }

    /**
     * Removes a tag.
     *
     * <p>The ref only. The tag object and the history beneath it stay stored, and
     * become collectible only if nothing else reaches them — which is the same
     * thing deleting a branch does.
     */
    public void delete(String owner, String name, User viewer, String tag) {
        VcsRepository repository = repositories.forWrite(owner, name, viewer);

        requireName(tag);
        if (!existsQuietly(repository, tag)) {
            throw new NotFoundException("Tag not found: " + tag);
        }
        if (releases.isReferenced(owner, name, tag)) {
            // Refused rather than cascaded. A release that lost its tag would be a
            // release nobody can check out, and silently deleting the release to
            // make the tag deletion succeed would destroy more than was asked for.
            throw new ConflictException(
                    "Tag " + tag + " is referenced by a release; delete the release first");
        }
        try {
            repository.tags().deleteTag(tag);
        } catch (RefException ex) {
            throw new BadRequestException(ex.getMessage());
        }
    }

    /**
     * A tag, its annotation if it has one, and the commit at the end of its chain.
     *
     * <p>Peeling is done quietly: a tag whose chain cannot be followed — because
     * an object beneath it is missing — is still a tag that exists, and a listing
     * that failed entirely because one entry was damaged would be least useful
     * exactly when it most needs reading.
     */
    private TagResponse describe(VcsRepository repository, String tag) {
        ObjectId target = repository.tags().getTag(tag)
                .orElseThrow(() -> new NotFoundException("Tag not found: " + tag));

        Optional<Tag> annotation = annotationQuietly(repository, tag);
        Optional<ObjectId> peeled = peelQuietly(repository, tag);

        return new TagResponse(
                tag,
                target.toHex(),
                peeled.map(ObjectId::toHex).orElse(null),
                annotation.isPresent(),
                annotation.map(Tag::message).orElse(null),
                annotation.map(a -> a.tagger().name()).orElse(null),
                annotation.map(a -> a.tagger().email()).orElse(null),
                annotation.map(a -> a.tagger().timestamp()).orElse(null),
                peeled.flatMap(id -> repository.reader().commit(id))
                        .map(BranchTipResponse::from)
                        .orElse(null));
    }

    private static boolean isAnnotated(CreateTagRequest request) {
        return request.message() != null && !request.message().isBlank();
    }

    private static void requireName(String tag) {
        if (tag == null || tag.isBlank()) {
            throw new BadRequestException("A tag name is required");
        }
    }

    private static boolean existsQuietly(VcsRepository repository, String tag) {
        try {
            return repository.tags().tagExists(tag);
        } catch (RefException ex) {
            return false;
        }
    }

    private static Optional<Tag> annotationQuietly(VcsRepository repository, String tag) {
        try {
            return repository.tags().annotationOf(tag);
        } catch (RefException ex) {
            return Optional.empty();
        }
    }

    private static Optional<ObjectId> peelQuietly(VcsRepository repository, String tag) {
        try {
            return repository.tags().peel(tag);
        } catch (RefException ex) {
            return Optional.empty();
        }
    }

    /** Tags are attributed to the authenticated caller, never to request data. */
    private static Signature signatureOf(User viewer) {
        return Signature.of(
                viewer.getDisplayName() == null || viewer.getDisplayName().isBlank()
                        ? viewer.getUsername()
                        : viewer.getDisplayName(),
                viewer.getEmail(),
                Instant.now());
    }
}
