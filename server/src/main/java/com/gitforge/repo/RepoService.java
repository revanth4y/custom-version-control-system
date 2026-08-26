package com.gitforge.repo;

import com.gitforge.common.error.ConflictException;
import com.gitforge.common.error.ForbiddenException;
import com.gitforge.common.error.NotFoundException;
import com.gitforge.repo.dto.CreateRepoRequest;
import com.gitforge.repo.dto.RepoResponse;
import com.gitforge.repo.dto.UpdateRepoRequest;
import com.gitforge.user.User;
import com.gitforge.user.UserService;
import com.gitforge.vcs.repository.RepositoryId;
import com.gitforge.vcs.repository.VcsRepositoryFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Repository metadata, and the single place where repository access rules are
 * enforced.
 *
 * <p>Two rules are applied consistently:
 * <ul>
 *   <li>A private repository is reported as <em>not found</em> to anyone but its
 *       owner, so its existence is not disclosed.</li>
 *   <li>Only the owner may modify a repository.</li>
 * </ul>
 */
@Service
public class RepoService {

    /** The branch a new repository's HEAD names before its first commit. */
    private static final String DEFAULT_BRANCH = "main";

    private static final Logger log = LoggerFactory.getLogger(RepoService.class);

    private final RepoRepository repoRepository;
    private final UserService userService;
    private final VcsRepositoryFactory vcsRepositoryFactory;

    public RepoService(
            RepoRepository repoRepository,
            UserService userService,
            VcsRepositoryFactory vcsRepositoryFactory) {
        this.repoRepository = repoRepository;
        this.userService = userService;
        this.vcsRepositoryFactory = vcsRepositoryFactory;
    }

    @Transactional
    public RepoResponse create(User owner, CreateRepoRequest request) {
        if (repoRepository.existsByOwnerAndNameIgnoreCase(owner, request.name())) {
            throw new ConflictException("You already own a repository named '" + request.name() + "'");
        }
        Repo repo = repoRepository.save(new Repo(owner, request.name(), request.description(), request.visibility()));

        // Version-control storage is created once the row exists, so the
        // repository has HEAD -> refs/heads/main from the moment it is visible.
        // The branch itself appears with the first commit.
        //
        // Filesystems are not transactional: if this transaction were to roll
        // back afterwards, the directory would remain without a row pointing at
        // it. Reclaiming such orphans is deliberately left to a later
        // maintenance phase rather than handled by deleting data here.
        vcsRepositoryFactory.initialise(
                RepositoryId.of(repo.getId().toString()), DEFAULT_BRANCH);

        return RepoResponse.from(repo);
    }

    /**
     * Loads a repository the viewer is allowed to read.
     *
     * @param viewer the authenticated caller, or null when anonymous
     */
    @Transactional(readOnly = true)
    public Repo requireReadable(String ownerUsername, String repoName, User viewer) {
        Repo repo = repoRepository.findByOwnerUsernameAndName(ownerUsername, repoName)
                .orElseThrow(() -> new NotFoundException("Repository not found"));

        if (!repo.isReadableBy(viewer)) {
            throw new NotFoundException("Repository not found");
        }
        return repo;
    }

    /**
     * Loads a repository the viewer is allowed to modify, by owner and name.
     *
     * <p>Applies the same two rules as the id-based form: an unreadable
     * repository is reported as missing before any permission problem is
     * mentioned, so its existence is never disclosed.
     */
    @Transactional(readOnly = true)
    public Repo requireWritable(String ownerUsername, String repoName, User viewer) {
        Repo repo = requireReadable(ownerUsername, repoName, viewer);
        if (!repo.isOwnedBy(viewer)) {
            throw new ForbiddenException("Only the repository owner may modify it");
        }
        return repo;
    }

    /** Loads a repository the viewer is allowed to modify. */
    @Transactional(readOnly = true)
    public Repo requireWritable(UUID repoId, User viewer) {
        Repo repo = repoRepository.findById(repoId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));

        // Hide the existence of unreadable repositories before reporting a
        // permission problem on readable ones.
        if (!repo.isReadableBy(viewer)) {
            throw new NotFoundException("Repository not found");
        }
        if (!repo.isOwnedBy(viewer)) {
            throw new ForbiddenException("Only the repository owner may modify it");
        }
        return repo;
    }

    @Transactional(readOnly = true)
    public RepoResponse get(String ownerUsername, String repoName, User viewer) {
        return RepoResponse.from(requireReadable(ownerUsername, repoName, viewer));
    }

    /** Repositories owned by {@code username}, filtered to what the viewer may see. */
    @Transactional(readOnly = true)
    public List<RepoResponse> listByOwner(String username, User viewer) {
        User owner = userService.requireByUsername(username);

        List<Repo> repos = owner.equals(viewer)
                ? repoRepository.findByOwnerOrderByUpdatedAtDesc(owner)
                : repoRepository.findByOwnerAndVisibilityOrderByUpdatedAtDesc(owner, RepoVisibility.PUBLIC);

        return repos.stream().map(RepoResponse::from).toList();
    }

    /** Public discovery listing. Private repositories never appear here. */
    @Transactional(readOnly = true)
    public Page<RepoResponse> listPublic(Pageable pageable) {
        return repoRepository.findByVisibility(RepoVisibility.PUBLIC, pageable)
                .map(RepoResponse::from);
    }

    @Transactional
    public RepoResponse update(UUID repoId, User viewer, UpdateRepoRequest request) {
        Repo repo = requireWritable(repoId, viewer);

        if (request.name() != null && !request.name().equalsIgnoreCase(repo.getName())) {
            if (repoRepository.existsByOwnerAndNameIgnoreCase(repo.getOwner(), request.name())) {
                throw new ConflictException("You already own a repository named '" + request.name() + "'");
            }
            repo.setName(request.name());
        }
        if (request.description() != null) {
            repo.setDescription(request.description());
        }
        if (request.visibility() != null) {
            repo.setVisibility(request.visibility());
        }

        return RepoResponse.from(repo);
    }

    /**
     * Removes a repository, record and contents alike.
     *
     * <p>Deleting only the row left every object on disk with nothing pointing
     * at it — invisible, unreachable, and permanent. What {@link #create} put
     * there, this takes away.
     *
     * <p>The row goes first and the storage after. The database is what decides
     * whether a repository exists, so if the deletion fails partway the surviving
     * state is a directory nobody can reach, which is what the old behaviour left
     * every time. The other order risks the opposite and worse: a row whose
     * storage is gone, which reads as an existing repository that has lost its
     * history — and would be silently re-created empty by the self-healing open.
     *
     * <p>For the same reason a storage failure does not fail the request. The
     * repository is gone as far as every caller is concerned, and turning that
     * into an error would report a deletion that did in fact happen as one that
     * did not. It is logged instead, which is what a later sweep would need.
     */
    @Transactional
    public void delete(UUID repoId, User viewer) {
        Repo repo = requireWritable(repoId, viewer);
        RepositoryId storageId = RepositoryId.of(repo.getId().toString());

        repoRepository.delete(repo);

        try {
            vcsRepositoryFactory.delete(storageId);
        } catch (IOException | RuntimeException ex) {
            log.warn("Deleted repository {} but could not remove its storage; it is now orphaned",
                    storageId, ex);
        }
    }
}
