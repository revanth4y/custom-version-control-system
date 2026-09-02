package com.gitforge.vcs.ref;

import com.gitforge.vcs.object.AmbiguousObjectIdException;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.repository.RepositoryLock;
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
    private final RepositoryLock lock;

    /**
     * Held so a revision can name a tag. Built here rather than injected: it is
     * stateless over the same two stores, so a separately-constructed one could
     * not disagree with this, and threading it through every caller would buy
     * nothing.
     */
    private final TagService tags;

    public BranchService(RefStore refStore, ObjectStore objectStore) {
        this(refStore, objectStore, new RepositoryLock());
    }

    public BranchService(RefStore refStore, ObjectStore objectStore, RepositoryLock lock) {
        if (refStore == null || objectStore == null) {
            throw new IllegalArgumentException("Branch service requires a reference store and an object store");
        }
        if (lock == null) {
            throw new IllegalArgumentException("Branch service requires a repository lock");
        }
        this.refStore = refStore;
        this.objectStore = objectStore;
        this.lock = lock;
        this.tags = new TagService(refStore, objectStore, lock);
    }

    /**
     * Creates a branch at an existing commit.
     *
     * @throws RefException if the name is invalid, the branch exists, or the
     *     target is not a commit in this repository
     */
    public void createBranch(String name, ObjectId startCommit) {
        // Shared with other writers, excluded from collection. A branch may be
        // created at a commit no other reference reaches — resurrecting a deleted
        // branch by id does exactly that — so this can turn an object a sweep was
        // about to collect into one it must keep.
        lock.shared(() -> {
            requireExistingCommit(startCommit);
            refStore.createBranch(name, startCommit);
        });
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
        // Excluded from collection for the same reason as createBranch: the target
        // need not have been reachable before.
        lock.shared(() -> {
            requireExistingCommit(commit);
            refStore.updateBranch(name, commit);
        });
    }

    /**
     * Deletes a branch.
     *
     * <p>Objects are never removed <em>here</em>. If no other branch can reach the
     * deleted branch's commit, that history becomes unreferenced: still stored,
     * still readable by id, but no longer named.
     *
     * <p>That remains true now that {@link com.gitforge.vcs.gc.GarbageCollector}
     * exists, and deliberately so. Deleting a branch is a reference operation;
     * reclaiming storage is a separate one somebody has to ask for. Making
     * deletion destroy history as a side effect would turn a reversible mistake
     * into an unrecoverable one, and it is what the retention tests covering this
     * method exist to prevent.
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
     * <p>Accepts {@code HEAD}, a branch name, a tag name, a full 40-character
     * commit id, or an unambiguous abbreviation of one.
     *
     * <p><strong>Order matters, and it is deliberate.</strong> It is:
     *
     * <pre>
     *   HEAD → branch → tag → full object id → abbreviated object id
     * </pre>
     *
     * <p>A branch is tried before a tag. Git resolves the other way round, and
     * this deliberately does not follow it: branches are the namespace callers
     * here name constantly, every existing call site passes one, and putting tags
     * first would silently change what those calls mean the day somebody tags a
     * name a branch already uses. The two namespaces are separate and a name may
     * exist in both; when it does, the mutable one people are working in wins, and
     * the tag stays reachable by every other means.
     *
     * <p>Both names take precedence over any id, because a name that happens to
     * look like a hash is still a name the user created — and the exact id is
     * tried before any abbreviation, so a caller who supplies all forty characters
     * is never put through a directory search to be told what they already knew.
     * A tag may not be <em>named</em> as a full object id at all; {@link TagName}
     * refuses that outright rather than leaving it to precedence.
     *
     * <p><strong>A tag resolves to what it ultimately names.</strong> An annotated
     * tag's ref points at a tag object, but a caller asking to resolve a revision
     * wants the commit, so the chain is peeled — through as many tag objects as it
     * takes. Reaching the tag object itself is what {@code TagService} is for.
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
        Optional<ObjectId> tag = tryGetTag(trimmed);
        if (tag.isPresent()) {
            return tag;
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

    /**
     * A tag, peeled to whatever it ultimately names.
     *
     * <p>Empty rather than throwing for a name no tag could have, so that a
     * revision string which is not a legal tag name simply moves on to being
     * tried as an id — the same shape {@link #tryGetBranch} uses.
     */
    private Optional<ObjectId> tryGetTag(String name) {
        try {
            return tags.peel(name);
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
