package com.gitforge.vcs.ref;

import com.gitforge.vcs.object.ObjectId;

/**
 * What the repository currently has checked out.
 *
 * <p>Two shapes, and the sealed hierarchy means every consumer must handle both:
 *
 * <pre>
 *   HEAD --ref:--&gt; refs/heads/main --&gt; &lt;commit&gt;   attached
 *   HEAD ------------------------------&gt; &lt;commit&gt;   detached
 * </pre>
 *
 * <p>Attached is the normal case: commits advance the branch HEAD names.
 * Detached points straight at a commit, which is how an arbitrary historical
 * revision can be inspected without inventing a branch for it. Modelling it as a
 * separate case rather than a nullable branch field keeps the distinction
 * explicit at every use site.
 */
public sealed interface Head permits Head.OnBranch, Head.Detached {

    /** HEAD follows a branch; the branch may not exist yet on a fresh repository. */
    record OnBranch(String branch) implements Head {
        public OnBranch {
            BranchName.validate(branch);
        }
    }

    /** HEAD names a commit directly. */
    record Detached(ObjectId commit) implements Head {
        public Detached {
            if (commit == null) {
                throw new RefException("Detached HEAD must name a commit");
            }
        }
    }

    static Head onBranch(String branch) {
        return new OnBranch(branch);
    }

    static Head detachedAt(ObjectId commit) {
        return new Detached(commit);
    }

    default boolean isDetached() {
        return this instanceof Detached;
    }
}
