package com.gitforge.vcsapi;

import com.gitforge.common.error.BadRequestException;
import com.gitforge.common.error.NotFoundException;
import com.gitforge.user.User;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.repository.VcsRepository;
import com.gitforge.vcsapi.dto.DiffResultResponse;
import com.gitforge.vcsapi.dto.FileDiffResponse;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Line-level diffs for the API.
 *
 * <p>All computation happens in the version-control engine; this only resolves
 * revisions and shapes the result. Diffing is a statement about repository
 * content, so every client must get the same answer for the same pair of blobs
 * — which cannot be guaranteed if each one computes its own.
 */
@Service
public class DiffApiService {

    private final VcsRepositoryProvider repositories;

    public DiffApiService(VcsRepositoryProvider repositories) {
        this.repositories = repositories;
    }

    /** Differences between two revisions. */
    public DiffResultResponse compare(
            String owner, String name, User viewer, String base, String head, String path) {

        VcsRepository repository = repositories.forRead(owner, name, viewer);

        if (base == null || base.isBlank() || head == null || head.isBlank()) {
            throw new BadRequestException("Both base and head revisions are required");
        }
        ObjectId baseTree = treeOf(repository, base);
        ObjectId headTree = treeOf(repository, head);

        List<FileDiffResponse> files = repository.diffs().diffTrees(baseTree, headTree, path).stream()
                .map(FileDiffResponse::from)
                .toList();

        return DiffResultResponse.of(base, head, files);
    }

    /** What a commit changed, against its first parent. */
    public DiffResultResponse commitDiff(String owner, String name, User viewer, String sha, String path) {
        VcsRepository repository = repositories.forRead(owner, name, viewer);

        ObjectId commitId = ObjectIds.resolve(repository, sha);
        if (repository.reader().commit(commitId).isEmpty()) {
            throw new NotFoundException("No such commit: " + sha);
        }

        List<FileDiffResponse> files = repository.diffs().diffCommit(commitId, path).stream()
                .map(FileDiffResponse::from)
                .toList();

        return DiffResultResponse.of(null, sha, files);
    }

    private static ObjectId treeOf(VcsRepository repository, String revision) {
        return repository.reader().resolve(revision)
                .map(commit -> repository.objects().readCommit(commit).tree())
                .orElseThrow(() -> new NotFoundException("Cannot resolve revision: " + revision));
    }

}
