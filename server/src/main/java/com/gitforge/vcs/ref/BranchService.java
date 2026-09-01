package com.gitforge.vcs.ref;

import com.gitforge.vcs.object.AmbiguousObjectIdException;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.storage.ObjectStore;

import java.util.List;
import java.util.Optional;

/**
 * The rules that govern branches, layered over raw reference storage.
 *
 * <p>{@link RefStore} deliberately accepts any object id, because a reference is
 * a pointer and pointers do not validate their targets. The rules that make a
 * branch meaningful live here: a branch must name a commit that exists, and the
 * branch currently checked out cannot be deleted from under HEAD.
 *
 * <p>Creating a branch writes one small file. No commit, tree, or blob is copied
 * — the history is already stored immutably and is simply shared by every branch
 * that can reach it, which is what makes branching free.
 */
public final class BranchService {

    private final RefStore refStore;
    private final ObjectStore objectStore;

    public BranchService(RefStore refStore, ObjectStore objectStore) {
        if (refStore == null || objectStore == null) {
            throw new IllegalArgumentException("Branch service requires a reference store and an object store");
        }
        this.refStore = refStore;
        this.objectStore = objectStore;
    }

    /**
     * Creates a branch at an existing commit.
     *
     * @throws RefException if the name is invalid, the branch exists, or the
     *     target is not a commit in this repository
     */
    public void createBranch(String name, ObjectId startCommit) {
        requireExistingCommit(startCommit);
        refStore.createBranch(name, startCommit);
    }

    /**
     * Creates a branch at whatever {@code startPoint} resolves to.
     *
     * @param startPoint a branch name, {@code HEAD}, or a full commit id
     */
    public void createBranchFrom(String name, String startPoint) {
        ObjectId commit = resolve(startPoint)
                .orElseThrow(() -> new RefException("Cannot resolve start point: " + startPoint));
        createBranch(name, commit);
    }

    public Optional<ObjectId> getBranch(String name) {
        return refStore.getBranch(name);
    }

    public boolean branchExists(String name) {
        return refStore.branchExists(name);
    }

    public List<String> listBranches() {
        return refStore.listBranches();
    }

    /**
     * Moves a branch to another commit.
     *
     * @throws RefException if the branch is absent or the target is not a commit
     */
    public void updateBranch(String name, ObjectId commit) {
        requireExistingCommit(commit);
        refStore.updateBranch(name, commit);
    }

    /**
     * Deletes a branch.
     *
     * <p>Objects are never removed. If no other branch can reach the deleted
     * branch's commit, that history simply becomes unreferenced: still stored,
     * still readable by id, but no longer named. Nothing is lost, because there
     * is no garbage collector to reclaim it — so refusing the deletion, as some
     * tools do, would protect against nothing here.
     *
     * @throws RefException if the branch does not exist or is checked out
     */
    public void deleteBranch(String name) {
        if (!refStore.branchExists(name)) {
            throw new RefException("Branch does not exist: " + name);
        }
        if (refStore.readHead() instanceof Head.OnBranch onBranch && onBranch.branch().equals(name)) {
            throw new RefException("Cannot delete the checked-out branch: " + name);
        }
        refStore.deleteBranch(name);
    }

    /** The branch HEAD is attached to, or empty when HEAD is detached. */
    public Optional<String> currentBranch() {
        return refStore.readHead() instanceof Head.OnBranch onBranch
                ? Optional.of(onBranch.branch())
                : Optional.empty();
    }

    public Head head() {
        return refStore.readHead();
    }

    /** The commit HEAD resolves to, or empty before the first commit. */
    public Optional<ObjectId> headCommit() {
        return refStore.resolveHead();
    }

    /**
     * Resolves a revision to a commit.
     *
     * <p>Accepts {@code HEAD}, a branch name, a full 40-character commit id, or
     * an unambiguous abbreviation of one.
     *
     * <p><strong>Order matters, and it is deliberate.</strong> A branch name
     * takes precedence over any id, because a name that happens to look like a
     * hash is still a name the user created — and the exact id is tried before
     * any abbreviation, so a caller who supplies all forty characters is never
     * put through a directory search to be told what they already knew.
     *
     * <p>An abbreviation that matches nothing is not found, the same as any
     * other unknown revision. One that matches several is a different situation
     * and is reported as such: see {@link AmbiguousObjectIdException}.
     *
     * <p>Any of those may carry a <strong>relative suffix</strong> — {@code ^n}
     * for a parent by position, {@code ~n} for a generation of first parents,
     * chained left to right, so {@code main~2^2} is the second parent of the
     * commit two back from {@code main}. The suffix is only considered once the
     * whole string has failed to name a commit outright, which is what keeps a
     * name ahead of an expression.
     *
     * @throws AmbiguousObjectIdException if an abbreviation names more than one
     *     object
     * @throws IllegalArgumentException if a relative expression is malformed —
     *     distinct from one that is merely unresolvable, which is empty
     */
    public Optional<ObjectId> resolve(String revision) {
        if (revision == null || revision.isBlank()) {
            return Optional.empty();
        }
        String trimmed = revision.trim();

        Optional<ObjectId> named = resolveNamed(trimmed);
        if (named.isPresent()) {
            return named;
        }
        // Only once the whole string has failed as a name of its own, so a ref
        // called "release^2" is that ref rather than a walk from "release".
        return resolveRelative(trimmed);
    }

    /** Every form that names a commit outright, in the order documented above. */
    private Optional<ObjectId> resolveNamed(String trimmed) {
        if (trimmed.equals("HEAD")) {
            return refStore.resolveHead();
        }
        Optional<ObjectId> branch = tryGetBranch(trimmed);
        if (branch.isPresent()) {
            return branch;
        }
        try {
            ObjectId id = ObjectId.fromHex(trimmed);
            if (objectStore.contains(id)) {
                return Optional.of(id);
            }
            // A full-length id that is not stored is simply absent. Falling
            // through to a prefix search would look it up again to no purpose.
            return Optional.empty();
        } catch (IllegalArgumentException ex) {
            // Not a complete id. It may still be the start of one.
            return resolveAbbreviated(trimmed);
        }
    }

    /**
     * Resolves a revision written relative to another, such as {@code HEAD~2}.
     *
     * <p><strong>Longest base first.</strong> Splitting at the first {@code ~}
     * or {@code ^} would be simpler and wrong: it would read a ref whose own
     * name contains one as a walk from some shorter name. {@link BranchName}
     * forbids both characters, so no branch created through this service can
     * contain them — but a reference is a file, and the rule that a name beats
     * an expression should not rest on the assumption that nothing ever wrote
     * one directly.
     *
     * <p>Three outcomes, and they are genuinely different. A base that resolves
     * and a walk that lands gives the commit. A base that does not resolve is
     * absent, exactly as the same base would be on its own. An expression the
     * grammar cannot read at any split is malformed — a question with no answer
     * rather than a question whose answer is nothing — and is refused.
     *
     * @throws IllegalArgumentException if the expression is not well formed
     */
    private Optional<ObjectId> resolveRelative(String revision) {
        // Counted over the whole expression rather than over one split of it.
        // A chain past the budget is unreadable however it is divided, and
        // saying so here also bounds the search below.
        int stepStarts = countStepStarts(revision);
        if (stepStarts > RevisionSuffix.MAX_STEPS) {
            throw new IllegalArgumentException("Malformed revision: " + revision);
        }

        boolean readable = false;

        // Descending, so the longest possible base is tried first.
        for (int split = revision.length() - 1; split > 0; split--) {
            if (!RevisionSuffix.isStepStart(revision.charAt(split))) {
                continue;
            }
            Optional<List<RevisionSuffix.Step>> steps =
                    RevisionSuffix.parse(revision.substring(split));
            if (steps.isEmpty()) {
                continue;
            }
            readable = true;

            Optional<ObjectId> start = resolveNamed(revision.substring(0, split));
            if (start.isPresent()) {
                return walk(start.get(), steps.get());
            }
        }

        if (!readable && containsStepStart(revision)) {
            throw new IllegalArgumentException("Malformed revision: " + revision);
        }
        return Optional.empty();
    }

    /**
     * Applies the steps in order.
     *
     * <p>Every failure here is an absence rather than a fault: a commit has the
     * parents it has, and history has a beginning. Asking for the second parent
     * of a commit with one, or for an ancestor further back than the root, is a
     * question about a commit that does not exist — the same answer as any other
     * unknown revision, and never an error about the walk itself.
     */
    private Optional<ObjectId> walk(ObjectId start, List<RevisionSuffix.Step> steps) {
        ObjectId current = start;

        for (RevisionSuffix.Step step : steps) {
            if (step.count() == 0) {
                // ^0 and ~0 are the commit itself, so nothing is read.
                continue;
            }
            if (step.kind() == RevisionSuffix.Step.Kind.PARENT) {
                List<ObjectId> parents = objectStore.readCommit(current).parents();
                if (step.count() > parents.size()) {
                    return Optional.empty();
                }
                current = parents.get(step.count() - 1);
                continue;
            }
            for (int generation = 0; generation < step.count(); generation++) {
                List<ObjectId> parents = objectStore.readCommit(current).parents();
                if (parents.isEmpty()) {
                    // Past the root: there is no such commit to name.
                    return Optional.empty();
                }
                current = parents.getFirst();
            }
        }
        return Optional.of(current);
    }

    private static boolean containsStepStart(String revision) {
        return countStepStarts(revision) > 0;
    }

    private static int countStepStarts(String revision) {
        int found = 0;
        for (int index = 0; index < revision.length(); index++) {
            if (RevisionSuffix.isStepStart(revision.charAt(index))) {
                found++;
            }
        }
        return found;
    }

    /**
     * Resolves an abbreviation, if the string could be one.
     *
     * <p>Anything too short, or not hexadecimal at all, is left as an unknown
     * revision rather than an error: the caller may simply have named a branch
     * that does not exist, and refusing "main" for not being hexadecimal would
     * be absurd. Length is what separates the two, and four characters is the
     * floor.
     */
    private Optional<ObjectId> resolveAbbreviated(String candidate) {
        if (!ObjectId.isValidPrefix(candidate)) {
            return Optional.empty();
        }
        List<ObjectId> matches = objectStore.findByPrefix(candidate);
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (matches.size() > 1) {
            throw new AmbiguousObjectIdException(candidate, matches);
        }
        return Optional.of(matches.getFirst());
    }

    /** A malformed name is simply not a branch, rather than an error, when resolving. */
    private Optional<ObjectId> tryGetBranch(String name) {
        try {
            return refStore.getBranch(name);
        } catch (RefException ex) {
            return Optional.empty();
        }
    }

    private void requireExistingCommit(ObjectId commit) {
        if (commit == null) {
            throw new RefException("A branch must point at a commit");
        }
        if (!objectStore.contains(commit)) {
            throw new RefException("No such commit in this repository: " + commit);
        }
        // Confirms the target really is a commit, not a tree or blob that
        // happens to be stored; readCommit raises if it is not.
        objectStore.readCommit(commit);
    }
}
