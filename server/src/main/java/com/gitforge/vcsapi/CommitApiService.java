package com.gitforge.vcsapi;

import com.gitforge.common.error.BadRequestException;
import com.gitforge.common.error.NotFoundException;
import com.gitforge.user.User;
import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Signature;
import com.gitforge.vcs.repository.FileChange;
import com.gitforge.vcs.repository.VcsRepository;
import com.gitforge.vcsapi.dto.CommitDetailResponse;
import com.gitforge.vcsapi.dto.CommitRequest;
import com.gitforge.vcsapi.dto.CommitSummaryResponse;
import com.gitforge.vcsapi.dto.CompareResponse;
import com.gitforge.vcsapi.dto.DiffResponse;
import com.gitforge.vcsapi.dto.FileChangeRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Recording and reading commits.
 *
 * <p>Commit identity comes from the engine's content addressing; nothing here
 * constructs or hashes a commit itself.
 */
@Service
public class CommitApiService {

    private static final int MAX_HISTORY = 200;

    /**
     * How large one file may be, from {@link ContentLimits}.
     *
     * <p>Read from there rather than restated here, so the figure cannot drift
     * apart from the one the read path enforces.
     */
    static final int MAX_BLOB_BYTES = ContentLimits.MAX_BLOB_BYTES;

    /**
     * Twelve megabytes across one commit.
     *
     * <p>Set to what a sixteen-megabyte request body can actually deliver once
     * base64 has taken its quarter, so the limit is reachable rather than
     * shadowed by the transport cap and never exercised.
     */
    static final int MAX_COMMIT_BYTES = 12 * 1024 * 1024;

    /**
     * Five hundred paths in one commit.
     *
     * <p>Five times the hundred files the differ will render hunks for, so
     * anything that commits successfully can still be reviewed. Beyond that it
     * is an import, which this API does not offer.
     */
    static final int MAX_CHANGES = 500;

    private final VcsRepositoryProvider repositories;

    public CommitApiService(VcsRepositoryProvider repositories) {
        this.repositories = repositories;
    }

    /**
     * Records several file changes as one commit.
     *
     * <p>Everything is measured and decoded before the engine is touched. A
     * request that is too large is refused having written no object and moved no
     * reference - the alternative is discovering the limit halfway through, with
     * some of the blobs already in the store.
     */
    public CommitSummaryResponse commit(String owner, String name, User viewer, CommitRequest request) {
        if (request.changes().size() > MAX_CHANGES) {
            throw new BadRequestException(
                    "A commit may change at most %d files; this one changes %d"
                            .formatted(MAX_CHANGES, request.changes().size()));
        }

        // Decoded once, measured, and only then turned into changes. Going
        // through FileChange first would clone every array to read its length.
        List<FileChange> changes = new ArrayList<>(request.changes().size());
        long total = 0;

        for (FileChangeRequest change : request.changes()) {
            if (!"PUT".equals(change.operation())) {
                changes.add(toFileChange(change, null));
                continue;
            }

            byte[] content = ContentApiService.decode(change.content(), change.encoding());
            if (content.length > MAX_BLOB_BYTES) {
                throw new BadRequestException(
                        "'%s' is %d bytes; a single file may be at most %d"
                                .formatted(change.path(), content.length, MAX_BLOB_BYTES));
            }
            total += content.length;
            if (total > MAX_COMMIT_BYTES) {
                throw new BadRequestException(
                        "A commit may carry at most %d bytes of content in total"
                                .formatted(MAX_COMMIT_BYTES));
            }
            changes.add(toFileChange(change, content));
        }

        return commit(owner, name, viewer, request.branch(), request.message(), changes);
    }

    CommitSummaryResponse commit(
            String owner, String name, User viewer, String branch, String message, List<FileChange> changes) {

        requireEachFileWithinLimit(changes);

        VcsRepository repository = repositories.forWrite(owner, name, viewer);

        ObjectId commitId;
        try {
            commitId = repository.commits().commit(branch, changes, signatureOf(viewer), message);
        } catch (IllegalArgumentException ex) {
            // Rejected change sets — an unknown path, a no-op commit, a path
            // colliding with a directory — are the caller's mistake.
            throw new BadRequestException(ex.getMessage());
        }
        return CommitSummaryResponse.from(repository.objects().readCommit(commitId));
    }

    /**
     * History reachable from a revision, optionally narrowed to one path.
     *
     * <p>Without a path this returns the {@code limit} most recent commits, and
     * limit is the only bound that matters. With one, the search looks back
     * through {@link #MAX_HISTORY} commits and returns the matches, up to limit.
     * The window has to be the wider of the two: a file touched once, long ago,
     * is still part of that file's history, and answering "no history" because
     * the last thirty commits did not mention it would be wrong in exactly the
     * way a reader could not detect.
     *
     * <p>The path is not checked for existence. A deleted file has history —
     * the commit that removed it touched that path — and demanding the path
     * exist at the revision would hide it.
     */
    public List<CommitSummaryResponse> history(
            String owner, String name, User viewer, String ref, Integer limit, String path) {

        VcsRepository repository = repositories.forRead(owner, name, viewer);

        int bounded = Math.clamp(limit == null ? 30 : limit, 1, MAX_HISTORY);
        String target = normalisePath(path);

        List<Commit> commits = target.isEmpty()
                ? repository.reader().history(revisionOrHead(ref), bounded)
                : repository.reader().historyForPath(revisionOrHead(ref), target, bounded, MAX_HISTORY);

        return commits.stream().map(CommitSummaryResponse::from).toList();
    }

    /**
     * A path as the engine expects it: no surrounding whitespace, no leading or
     * trailing slashes.
     *
     * <p>{@code /src/} and {@code src} name the same directory to anyone typing
     * a path, but only the second matches what the differ reports, so the
     * difference has to be removed here rather than surprise the caller. What
     * normalises to nothing is the repository root, whose history is the whole
     * history — not an error, and not a filter that silently matches nothing.
     */
    private static String normalisePath(String path) {
        if (path == null) {
            return "";
        }
        return path.trim().replaceAll("^/+", "").replaceAll("/+$", "");
    }

    public CommitDetailResponse detail(String owner, String name, User viewer, String sha) {
        VcsRepository repository = repositories.forRead(owner, name, viewer);

        ObjectId commitId = parseObjectId(sha);
        Commit commit = repository.reader().commit(commitId)
                .orElseThrow(() -> new NotFoundException("No such commit: " + sha));

        return new CommitDetailResponse(
                CommitSummaryResponse.from(commit),
                DiffResponse.from(repository.reader().changesIn(commitId)));
    }

    public CompareResponse compare(String owner, String name, User viewer, String base, String head) {
        VcsRepository repository = repositories.forRead(owner, name, viewer);

        if (base == null || base.isBlank() || head == null || head.isBlank()) {
            throw new BadRequestException("Both base and head revisions are required");
        }
        return repository.reader().compare(base, head)
                .map(diff -> new CompareResponse(base, head, DiffResponse.from(diff)))
                .orElseThrow(() -> new NotFoundException("Cannot resolve one of the revisions to compare"));
    }

    /**
     * Refuses any single file over the limit, before the engine is touched.
     *
     * <p>Every write reaches the engine through here, which is the reason the
     * check sits at this level rather than beside each caller. Writing a single
     * file used to arrive with no size check at all: the measuring above happens
     * on the way in from a commit request, and {@code PUT /contents} does not
     * come that way. A file too large to read back could therefore be stored,
     * and the only endpoint that serves file contents would then refuse it.
     *
     * <p>Measured through {@link FileChange#size()}, which does not copy the
     * content to find out how much of it there is.
     *
     * <p>A bad request rather than a payload-too-large, which is what this path
     * has always answered for an oversized file and what its callers expect.
     */
    private static void requireEachFileWithinLimit(List<FileChange> changes) {
        for (FileChange change : changes) {
            if (!ContentLimits.withinBlobLimit(change.size())) {
                throw new BadRequestException(
                        "'%s' is %d bytes; a single file may be at most %d"
                                .formatted(change.path(), change.size(), ContentLimits.MAX_BLOB_BYTES));
            }
        }
    }

    /** @param decoded the already-decoded content for a PUT, null for a DELETE */
    private static FileChange toFileChange(FileChangeRequest request, byte[] decoded) {
        return switch (request.operation()) {
            case "PUT" -> FileChange.put(request.path(), decoded, ContentApiService.modeOf(request.mode()));
            case "DELETE" -> FileChange.delete(request.path());
            default -> throw new BadRequestException("Unsupported operation: " + request.operation());
        };
    }

    /** Commits are attributed to the authenticated caller, never to request data. */
    private Signature signatureOf(User viewer) {
        return Signature.of(
                viewer.getDisplayName() == null || viewer.getDisplayName().isBlank()
                        ? viewer.getUsername()
                        : viewer.getDisplayName(),
                viewer.getEmail(),
                Instant.now());
    }

    private static ObjectId parseObjectId(String sha) {
        try {
            return ObjectId.fromHex(sha);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Not a valid commit id: " + sha);
        }
    }

    private static String revisionOrHead(String ref) {
        return ref == null || ref.isBlank() ? "HEAD" : ref;
    }
}
