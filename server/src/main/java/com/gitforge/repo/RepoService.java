package com.gitforge.repo;

import com.gitforge.common.error.ConflictException;
import com.gitforge.common.error.ForbiddenException;
import com.gitforge.common.error.NotFoundException;
import com.gitforge.repo.dto.CreateRepoRequest;
import com.gitforge.repo.dto.RepoResponse;
import com.gitforge.repo.dto.UpdateRepoRequest;
import com.gitforge.user.User;
import com.gitforge.user.UserService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final RepoRepository repoRepository;
    private final UserService userService;

    public RepoService(RepoRepository repoRepository, UserService userService) {
        this.repoRepository = repoRepository;
        this.userService = userService;
    }

    @Transactional
    public RepoResponse create(User owner, CreateRepoRequest request) {
        if (repoRepository.existsByOwnerAndNameIgnoreCase(owner, request.name())) {
            throw new ConflictException("You already own a repository named '" + request.name() + "'");
        }
        Repo repo = new Repo(owner, request.name(), request.description(), request.visibility());
        return RepoResponse.from(repoRepository.save(repo));
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

/** Loads a repository named by owner/name that the viewer may modify. */
@Transactional(readOnly = true)
public Repo requireWritable(String ownerUsername, String repoName, User viewer) {
    Repo repo = repoRepository.findByOwnerUsernameAndName(ownerUsername, repoName)
            .orElseThrow(() -> new NotFoundException("Repository not found"));

    if (!repo.isReadableBy(viewer)) {
        throw new NotFoundException("Repository not found");
    }

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

    @Transactional
    public void delete(UUID repoId, User viewer) {
        Repo repo = requireWritable(repoId, viewer);
        repoRepository.delete(repo);
    }
}
