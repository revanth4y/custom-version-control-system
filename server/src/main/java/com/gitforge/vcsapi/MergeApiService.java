package com.gitforge.vcsapi;

import com.gitforge.common.error.NotFoundException;
import com.gitforge.user.User;
import com.gitforge.vcs.object.Signature;
import com.gitforge.vcs.ref.RefException;
import com.gitforge.vcs.repository.MergeOutcome;
import com.gitforge.vcs.repository.VcsRepository;
import com.gitforge.vcsapi.dto.MergeRequest;
import com.gitforge.vcsapi.dto.MergeResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Merging one branch into another.
 *
 * <p>A conflicted merge is a legitimate answer rather than a failure: the engine
 * writes nothing and moves nothing, and the caller is told precisely which paths
 * disagree. The controller turns that into a 409, since the request could not be
 * carried out in the state the repository is in.
 */
@Service
public class MergeApiService {

    private final VcsRepositoryProvider repositories;

    public MergeApiService(VcsRepositoryProvider repositories) {
        this.repositories = repositories;
    }

    public MergeResponse merge(String owner, String name, User viewer, MergeRequest request) {
        VcsRepository repository = repositories.forWrite(owner, name, viewer);

        MergeOutcome outcome;
        try {
            outcome = repository.merges().merge(
                    request.ourBranch(),
                    request.theirBranch(),
                    signatureOf(viewer),
                    request.message());
        } catch (RefException ex) {
            // The only reference failure reachable here is a branch that is not
            // there, since nothing else is touched before the merge runs.
            throw new NotFoundException(ex.getMessage());
        }
        return MergeResponse.from(outcome);
    }

    private Signature signatureOf(User viewer) {
        return Signature.of(
                viewer.getDisplayName() == null || viewer.getDisplayName().isBlank()
                        ? viewer.getUsername()
                        : viewer.getDisplayName(),
                viewer.getEmail(),
                Instant.now());
    }
}
