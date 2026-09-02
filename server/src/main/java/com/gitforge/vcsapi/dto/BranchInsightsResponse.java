package com.gitforge.vcsapi.dto;

import java.util.List;

/**
 * How far each branch has drifted from whatever HEAD resolves to.
 *
 * @param base what everything was measured against, or null for a repository
 *     with nothing checked out
 */
public record BranchInsightsResponse(String base, int total, List<Branch> branches) {

    /**
     * @param ahead commits this branch has that the base does not
     * @param behind commits the base has that this branch does not
     * @param related whether the two histories share any commit; false means the
     *     two counts are whole histories rather than a divergence
     */
    public record Branch(
            String name,
            String tip,
            int ahead,
            int behind,
            boolean current,
            boolean related) {
    }
}
