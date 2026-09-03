package com.gitforge.vcs.ref;

import com.gitforge.vcs.object.ObjectId;

/**
 * Where a remote's branch stood the last time this repository looked.
 *
 * <p>Not a branch. Nothing commits to it, nothing can check it out, and it moves
 * only when a fetch says the remote moved. Keeping it in its own type rather than
 * as a specially-named branch is what stops it appearing in
 * {@code listBranches()}, in branch resolution, or in the statistics — all of
 * which describe local development, which this is not part of.
 *
 * <p>It is, however, a garbage-collection root. An object reachable only through
 * a fetched tip is still an object this repository asked for and can still show.
 *
 * @param remote the remote it was fetched from
 * @param branch the branch name on that remote
 * @param commit where that branch pointed
 */
public record RemoteRef(String remote, String branch, ObjectId commit) {

    public RemoteRef {
        RemoteName.validate(remote);
        BranchName.validate(branch);
        if (commit == null) {
            throw new RefException("Remote ref must name a commit: " + remote + "/" + branch);
        }
    }

    /** {@code origin/main} — how the pair reads to a person. */
    public String qualifiedName() {
        return remote + "/" + branch;
    }
}
