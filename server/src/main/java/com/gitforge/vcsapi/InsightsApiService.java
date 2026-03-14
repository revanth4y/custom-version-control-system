package com.gitforge.vcsapi;

import com.gitforge.user.User;
import com.gitforge.vcsapi.dto.InsightsResponse;
import org.springframework.stereotype.Service;

/**
 * Repository statistics for the API.
 *
 * <p>Aggregation lives in the version-control engine, which can compute it
 * without an application context; this only applies authorization and shapes the
 * result.
 */
@Service
public class InsightsApiService {

    private final VcsRepositoryProvider repositories;

    public InsightsApiService(VcsRepositoryProvider repositories) {
        this.repositories = repositories;
    }

    public InsightsResponse insights(String owner, String name, User viewer) {
        return InsightsResponse.from(
                repositories.forRead(owner, name, viewer).statistics().compute());
    }
}
