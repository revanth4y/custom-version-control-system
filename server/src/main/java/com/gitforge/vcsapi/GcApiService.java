package com.gitforge.vcsapi;

import com.gitforge.user.User;
import com.gitforge.vcsapi.dto.GcResponse;
import org.springframework.stereotype.Service;

/**
 * Reporting and reclaiming unreferenced storage, over HTTP.
 *
 * <p>Thin on purpose. Everything that decides what may be deleted lives in
 * {@link com.gitforge.vcs.gc.GarbageCollector}, inside the engine, where the
 * object model it reasons about actually is. A second reachability calculation at
 * this layer is exactly the kind of duplicate that drifts from the one that
 * matters.
 *
 * <p>What this layer contributes is the authorization split, which is not the
 * engine's business:
 *
 * <ul>
 *   <li>reporting follows {@link VcsRepositoryProvider#forRead} — the same
 *       visibility rule as every other repository read, and the same one the
 *       integrity endpoint uses;
 *   <li>collecting follows {@link VcsRepositoryProvider#forWrite} — owner-only,
 *       because it destroys data.
 * </ul>
 */
@Service
public class GcApiService {

    private final VcsRepositoryProvider repositories;

    public GcApiService(VcsRepositoryProvider repositories) {
        this.repositories = repositories;
    }

    /** What a collection would remove. Removes nothing. */
    public GcResponse report(String owner, String name, User viewer) {
        return GcResponse.from(repositories.forRead(owner, name, viewer).gc().report());
    }

    /** Removes every object no reference reaches. Owner-only. */
    public GcResponse collect(String owner, String name, User viewer) {
        return GcResponse.from(repositories.forWrite(owner, name, viewer).gc().collect());
    }
}
