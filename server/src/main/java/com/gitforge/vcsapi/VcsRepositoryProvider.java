package com.gitforge.vcsapi;

import com.gitforge.repo.Repo;
import com.gitforge.repo.RepoService;
import com.gitforge.user.User;
import com.gitforge.vcs.repository.RepositoryId;
import com.gitforge.vcs.repository.VcsRepository;
import com.gitforge.vcs.repository.VcsRepositoryFactory;
import org.springframework.stereotype.Component;

/**
 * The only way to obtain a {@link VcsRepository}, and therefore the one place
 * repository access is authorised.
 *
 * <p>Both methods resolve the repository through {@link RepoService}, which
 * applies the existing visibility and ownership rules, before touching storage.
 * Because there is no other route to a repository handle, an endpoint added
 * later cannot forget the check: it has nothing to call that skips it. That is a
 * stronger guarantee than a convention every controller has to remember.
 *
 * <p>The storage id is always derived from the database row, never taken from
 * the request. A client names {@code owner/name}; the identifier that reaches
 * the filesystem is the repository's own UUID.
 */
@Component
public class VcsRepositoryProvider {

    /** The branch a new repository's HEAD points at, before any commit exists. */
    public static final String DEFAULT_BRANCH = "main";

    private final RepoService repoService;
    private final VcsRepositoryFactory factory;

    public VcsRepositoryProvider(RepoService repoService, VcsRepositoryFactory factory) {
        this.repoService = repoService;
        this.factory = factory;
    }

    /**
     * Opens a repository the viewer may read.
     *
     * @param viewer the authenticated caller, or null when anonymous
     * @throws com.gitforge.common.error.NotFoundException if it does not exist, or
     *     is private and not theirs
     */
    public VcsRepository forRead(String owner, String name, User viewer) {
        return open(repoService.requireReadable(owner, name, viewer));
    }

    /**
     * Opens a repository the viewer may modify.
     *
     * @throws com.gitforge.common.error.NotFoundException if it does not exist, or
     *     is private and not theirs
     * @throws com.gitforge.common.error.ForbiddenException if they are not the owner
     */
    public VcsRepository forWrite(String owner, String name, User viewer) {
        return open(repoService.requireWritable(owner, name, viewer));
    }

    /** The storage identifier for a repository row. */
    public static RepositoryId storageIdOf(Repo repo) {
        return RepositoryId.of(repo.getId().toString());
    }

    /**
     * Opens storage, creating it if it is absent.
     *
     * <p>Self-healing rather than failing: a repository row whose storage was
     * never created — one predating this feature, or left behind by a rollback —
     * behaves as an empty repository instead of returning an error the user
     * cannot act on.
     */
    private VcsRepository open(Repo repo) {
        return factory.openOrInitialise(storageIdOf(repo), DEFAULT_BRANCH);
    }
}
