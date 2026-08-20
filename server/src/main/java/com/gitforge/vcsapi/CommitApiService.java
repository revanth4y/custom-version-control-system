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

    private final VcsRepositoryProvider repositories;

    public CommitApiService(VcsRepositoryProvider repositories) {
        this.repositories = repositories;
    }

    /** Records several file changes as one commit. */
    public CommitSummaryResponse commit(String owner, String name, User viewer, CommitRequest request) {
        List<FileChange> changes = request.changes().stream()
                .map(CommitApiService::toFileChange)
                .toList();

        return commit(owner, name, viewer, request.branch(), request.message(), changes);
    }

    CommitSummaryResponse commit(
            String owner, String name, User viewer, String branch, String message, List<FileChange> changes) {

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

    public List<CommitSummaryResponse> history(String owner, String name, User viewer, String ref, Integer limit) {
        VcsRepository repository = repositories.forRead(owner, name, viewer);

        int bounded = Math.clamp(limit == null ? 30 : limit, 1, MAX_HISTORY);
        return repository.reader().history(revisionOrHead(ref), bounded).stream()
                .map(CommitSummaryResponse::from)
                .toList();
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

    private static FileChange toFileChange(FileChangeRequest request) {
        return switch (request.operation()) {
            case "PUT" -> FileChange.put(
                    request.path(),
                    ContentApiService.decode(request.content(), request.encoding()),
                    ContentApiService.modeOf(request.mode()));
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
