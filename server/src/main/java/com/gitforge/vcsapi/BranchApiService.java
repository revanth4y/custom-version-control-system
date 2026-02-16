package com.gitforge.vcsapi;

import com.gitforge.common.error.BadRequestException;
import com.gitforge.common.error.ConflictException;
import com.gitforge.common.error.NotFoundException;
import com.gitforge.user.User;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.ref.Head;
import com.gitforge.vcs.ref.RefException;
import com.gitforge.vcs.repository.VcsRepository;
import com.gitforge.vcsapi.dto.BranchResponse;
import com.gitforge.vcsapi.dto.CreateBranchRequest;
import com.gitforge.vcsapi.dto.HeadResponse;
import com.gitforge.vcsapi.dto.SetHeadRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Branch and HEAD operations for the API.
 *
 * <p>Sits between the controller and the version-control engine, and is where
 * engine failures become HTTP meanings. The engine raises one
 * {@link RefException} for several situations — absent, duplicate, checked out —
 * because they are all "the reference is not in the state you assumed". Deciding
 * that an absent branch is a 404 while a duplicate is a 409 is an HTTP concern,
 * so it is made here rather than pushed into the engine.
 */
@Service
public class BranchApiService {

    private final VcsRepositoryProvider repositories;

    public BranchApiService(VcsRepositoryProvider repositories) {
        this.repositories = repositories;
    }

    public List<BranchResponse> list(String owner, String name, User viewer) {
        VcsRepository repository = repositories.forRead(owner, name, viewer);
        Optional<String> current = repository.branches().currentBranch();

        return repository.branches().listBranches().stream()
                .map(branch -> new BranchResponse(
                        branch,
                        repository.branches().getBranch(branch).map(ObjectId::toHex).orElse(null),
                        current.filter(branch::equals).isPresent()))
                .toList();
    }

    public BranchResponse create(String owner, String name, User viewer, CreateBranchRequest request) {
        VcsRepository repository = repositories.forWrite(owner, name, viewer);

        // Checked quietly: a malformed name is not an existing branch, and must
        // be reported as a bad request rather than as a conflict.
        if (existsQuietly(repository, request.name())) {
            throw new ConflictException("Branch already exists: " + request.name());
        }
        ObjectId startCommit = repository.branches().resolve(request.startPoint())
                .orElseThrow(() -> new NotFoundException("Cannot resolve start point: " + request.startPoint()));

        try {
            repository.branches().createBranch(request.name(), startCommit);
        } catch (RefException ex) {
            // Whatever remains is a malformed name, since existence and the
            // start point were both settled above.
            throw new BadRequestException(ex.getMessage());
        }
        return new BranchResponse(request.name(), startCommit.toHex(), false);
    }

    public void delete(String owner, String name, User viewer, String branch) {
        VcsRepository repository = repositories.forWrite(owner, name, viewer);

        if (branch == null || branch.isBlank()) {
            throw new BadRequestException("A branch name is required");
        }
        if (!existsQuietly(repository, branch)) {
            throw new NotFoundException("Branch not found: " + branch);
        }
        try {
            repository.branches().deleteBranch(branch);
        } catch (RefException ex) {
            // The remaining refusal is deleting the branch HEAD points at.
            throw new ConflictException(ex.getMessage());
        }
    }

    public HeadResponse head(String owner, String name, User viewer) {
        VcsRepository repository = repositories.forRead(owner, name, viewer);
        Head head = repository.branches().head();

        String commit = repository.branches().headCommit().map(ObjectId::toHex).orElse(null);
        return switch (head) {
            case Head.OnBranch onBranch -> new HeadResponse(onBranch.branch(), commit, false);
            case Head.Detached detached -> new HeadResponse(null, detached.commit().toHex(), true);
        };
    }

    /**
     * Points HEAD at a branch.
     *
     * <p>Server-side repositories are bare, so this records which branch the
     * repository is "on" rather than materialising any files.
     */
    public HeadResponse setHead(String owner, String name, User viewer, SetHeadRequest request) {
        VcsRepository repository = repositories.forWrite(owner, name, viewer);

        if (!existsQuietly(repository, request.branch())) {
            throw new NotFoundException("Branch not found: " + request.branch());
        }
        repository.refs().setHead(Head.onBranch(request.branch()));

        return new HeadResponse(
                request.branch(),
                repository.branches().headCommit().map(ObjectId::toHex).orElse(null),
                false);
    }

    /** A malformed name is simply not an existing branch, rather than an error. */
    private static boolean existsQuietly(VcsRepository repository, String branch) {
        try {
            return repository.branches().branchExists(branch);
        } catch (RefException ex) {
            return false;
        }
    }
}
