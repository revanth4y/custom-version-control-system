package com.gitforge.vcs.repository;

import com.gitforge.vcs.diff.TreeDiff;
import com.gitforge.vcs.diff.TreeDiffer;
import com.gitforge.vcs.graph.CommitGraph;
import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Tree;
import com.gitforge.vcs.object.TreeEntry;
import com.gitforge.vcs.ref.BranchService;
import com.gitforge.vcs.storage.ObjectStore;
import com.gitforge.vcs.tree.TreeWalker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reading a repository without changing it.
 *
 * <p>Everything here is served from the immutable object store. There is no
 * working tree to consult and nothing is materialised to disk to answer a
 * question: browsing a directory reads one tree, and reading a file reads one
 * tree per path segment plus one blob.
 *
 * <p>Revisions are accepted wherever a commit is expected — {@code HEAD}, a
 * branch name, or a full commit id — resolved by the existing branch service so
 * the rules live in one place.
 */
public final class RepositoryReader {

    private static final ObjectId EMPTY_TREE_ID = Tree.empty().id();

    private final ObjectStore objects;
    private final BranchService branches;
    private final CommitGraph graph;
    private final TreeWalker walker;
    private final TreeDiffer differ;

    RepositoryReader(ObjectStore objects, BranchService branches, CommitGraph graph) {
        this.objects = objects;
        this.branches = branches;
        this.graph = graph;
        this.walker = new TreeWalker(objects);
        this.differ = new TreeDiffer(objects);
    }

    /** Resolves {@code HEAD}, a branch name, or a commit id to a commit. */
    public Optional<ObjectId> resolve(String revision) {
        return branches.resolve(revision);
    }

    /** True once the repository has at least one commit on the branch HEAD names. */
    public boolean hasCommits() {
        return branches.headCommit().isPresent();
    }

    /**
     * Lists the immediate contents of a directory.
     *
     * @param path the directory, or empty/{@code "/"} for the repository root
     * @return the entries, or empty if the revision or path does not exist
     */
    public Optional<List<TreeEntry>> listDirectory(String revision, String path) {
        Optional<ObjectId> rootTree = rootTreeOf(revision);
        if (rootTree.isEmpty()) {
            return Optional.empty();
        }
        if (isRoot(path)) {
            return Optional.of(readTree(rootTree.get()).entries());
        }
        return walker.resolve(rootTree.get(), path)
                .filter(TreeEntry::isDirectory)
                .map(entry -> readTree(entry.id()).entries());
    }

    /** The entry at a path, whether file or directory. */
    public Optional<TreeEntry> entryAt(String revision, String path) {
        if (isRoot(path)) {
            return Optional.empty();
        }
        return rootTreeOf(revision).flatMap(tree -> walker.resolve(tree, path));
    }

    /**
     * Reads a file's contents.
     *
     * @return empty if the revision or path does not exist, or names a directory
     */
    public Optional<byte[]> readFile(String revision, String path) {
        return entryAt(revision, path)
                .filter(entry -> !entry.isDirectory())
                .map(entry -> objects.readBlob(entry.id()).payload());
    }

    /** Every file in a revision, as full paths. */
    public List<TreeWalker.Entry> listAllFiles(String revision) {
        return rootTreeOf(revision).map(walker::flatten).orElseGet(List::of);
    }

    /**
     * Commit history reachable from a revision.
     *
     * <p>Ordered by distance from the starting commit — the breadth-first order
     * from the commit graph — so a linear history reads newest to oldest, and a
     * merge is followed by both of its parents before their shared ancestry.
     *
     * @param limit the greatest number of commits to return
     */
    public List<Commit> history(String revision, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("History limit must be positive");
        }
        return resolve(revision)
                .map(start -> graph.walk(start)
                        .limit(limit)
                        .map(objects::readCommit)
                        .toList())
                .orElseGet(List::of);
    }

    /**
     * A page of history from a known starting commit.
     *
     * <p>Takes the commit rather than a revision because a paged walk must be a
     * walk over one snapshot. Resolving the ref again on every page would let a
     * branch move underneath the traversal, and the client would receive commits
     * that skip or repeat with nothing in the response to indicate it.
     *
     * @param start  the commit the walk began from, already resolved
     * @param offset how many commits of that walk the caller has already seen
     * @param limit  the greatest number of commits to return
     */
    public HistorySlice historyPage(ObjectId start, int offset, int limit) {
        requirePaging(offset, limit);

        // One more than asked for: whether history continues is answered by
        // trying to take a commit past the page, not by a second traversal.
        List<Commit> found = graph.walk(start)
                .skip(offset)
                .limit(limit + 1L)
                .map(objects::readCommit)
                .toList();

        boolean moreHistory = found.size() > limit;
        List<Commit> page = moreHistory ? found.subList(0, limit) : found;
        return new HistorySlice(page, page.size(), moreHistory);
    }

    /**
     * A page of history for one path, from a known starting commit.
     *
     * <p>The budget bounds the work, not the history. A page that spends its
     * whole budget without filling reports {@code moreHistory} anyway, so the
     * caller can continue rather than conclude the file has no earlier history —
     * which is exactly the wrong conclusion, and the one the unpaged endpoint
     * could not avoid drawing.
     *
     * @param budget the greatest number of commits to examine for this page
     */
    public HistorySlice historyPageForPath(
            ObjectId start, String path, int offset, int limit, int budget) {

        requirePaging(offset, limit);
        if (budget <= 0) {
            throw new IllegalArgumentException("History budget must be positive");
        }
        if (path == null || path.isBlank()) {
            return historyPage(start, offset, limit);
        }

        List<Commit> matches = new ArrayList<>();
        int walked = 0;
        boolean moreHistory = false;

        // Held open only as long as the page needs it: the walk reads commits as
        // it is consumed, and stops the moment the page is full.
        try (Stream<ObjectId> walk = graph.walk(start).skip(offset)) {
            Iterator<ObjectId> remaining = walk.iterator();

            while (remaining.hasNext()) {
                if (matches.size() == limit || walked == budget) {
                    // Something is left, whether or not the page filled.
                    moreHistory = true;
                    break;
                }
                Commit commit = objects.readCommit(remaining.next());
                walked++;

                boolean touched = changedPaths(commit).stream()
                        .anyMatch(changed -> touches(changed, path));
                if (touched) {
                    matches.add(commit);
                }
            }
        }
        return new HistorySlice(matches, walked, moreHistory);
    }

    private static void requirePaging(int offset, int limit) {
        if (offset < 0) {
            throw new IllegalArgumentException("History offset must not be negative");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("History limit must be positive");
        }
    }

    public Optional<Commit> commit(ObjectId id) {
        return objects.read(id)
                .filter(Commit.class::isInstance)
                .map(Commit.class::cast);
    }

    /**
     * What a commit changed, compared with its first parent.
     *
     * <p>An initial commit is compared against the empty tree, so its every file
     * is reported as an addition rather than requiring the caller to special-case
     * it. For a merge, only the first parent is used — the second parent's
     * changes are by definition already present on the branch being merged into.
     */
    public TreeDiff changesIn(ObjectId commitId) {
        Commit commit = objects.readCommit(commitId);
        ObjectId parentTree = commit.parents().isEmpty()
                ? EMPTY_TREE_ID
                : objects.readCommit(commit.parents().getFirst()).tree();

        return differ.diff(parentTree, commit.tree());
    }

    /**
     * The most recent commit to touch each of the given paths.
     *
     * <p>Answers the question a file listing asks: for every entry shown, which
     * commit last changed it and when. A directory counts as touched when
     * anything beneath it changed, which is what makes the column useful - a
     * directory almost never changes in its own right.
     *
     * <p>The search is <strong>bounded</strong> to the {@code limit} most recent
     * commits reachable from {@code revision}. A path not touched within that
     * window is simply absent from the result rather than reported wrongly; the
     * caller is expected to render that as unknown. Without a bound this would
     * walk the entire history for every directory listing.
     *
     * <p>Commits are examined in the order {@link #history(String, int)} returns
     * them - nearest the tip first - so the first one seen to touch a path is
     * the answer and the walk can stop as soon as every path is accounted for.
     * That is deliberately the same order the history listing shows, so the
     * commit named against a file here is the one a reader will find at the top
     * of that file's history.
     *
     * <p>Sorting by timestamp instead would be wrong: signatures are stored to
     * one-second resolution, following Git, so two commits made in the same
     * second are indistinguishable by time and the tie-break decides
     * attribution arbitrarily. Graph order has no such ambiguity.
     *
     * <p>Attribution uses {@link #changesIn(ObjectId)}, so a merge is credited
     * with everything it brought in relative to its first parent. That matches
     * how the rest of this class reads history and keeps one definition of "what
     * a commit changed".
     *
     * @param paths repository-relative paths, as they appear in a listing
     * @param limit how many commits to look back through; must be positive
     * @return the touching commit for each path that was resolved
     */
    public Map<String, Commit> lastCommits(String revision, Collection<String> paths, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("History limit must be positive");
        }
        if (paths == null || paths.isEmpty()) {
            return Map.of();
        }

        Set<String> unresolved = new HashSet<>(paths);
        unresolved.remove(null);
        unresolved.remove("");
        if (unresolved.isEmpty()) {
            return Map.of();
        }

        Map<String, Commit> resolved = new HashMap<>();

        for (Commit commit : history(revision, limit)) {
            for (String changed : changedPaths(commit)) {
                // Copied because a hit removes from the set being iterated.
                for (String candidate : List.copyOf(unresolved)) {
                    if (touches(changed, candidate)) {
                        resolved.put(candidate, commit);
                        unresolved.remove(candidate);
                    }
                }
            }
            if (unresolved.isEmpty()) {
                // Everything is accounted for; older commits cannot change that.
                break;
            }
        }
        return Map.copyOf(resolved);
    }

    /**
     * History for one path: the commits that touched it, newest first.
     *
     * <p>Answers what a file's history page asks. A directory counts as touched
     * when anything beneath it changed, the same rule {@link #lastCommits} uses,
     * so the two never disagree about who last changed what.
     *
     * <p><strong>Two bounds, because they answer different questions.</strong>
     * {@code limit} is how many matching commits to return; {@code window} is how
     * far back to look for them. They have to be separate: a file touched once,
     * eighty commits ago, is found by a window of two hundred and missed by a
     * window of thirty, and a caller asking for thirty results should not be told
     * the file has no history because thirty recent commits happened not to
     * mention it. Unfiltered history needs only one bound because every commit
     * matches, so the two collapse into each other there.
     *
     * <p>An empty result therefore means <em>not touched within the window</em>,
     * never <em>never changed</em>. The caller is expected to say so.
     *
     * <p><strong>History stops at a rename.</strong> The differ pairs nothing by
     * content, so a rename is a delete of one path and an addition of another.
     * Asking for the new path returns the commit that added it and nothing
     * earlier — the file's life under its old name is real history that this
     * cannot reach. That is a limitation to state, not to paper over by guessing
     * which delete belongs to which addition.
     *
     * <p>A blank path is the repository root, which every commit touches, so it
     * is the whole history rather than an error.
     *
     * @param path repository-relative, without leading or trailing slashes
     * @param limit how many matching commits to return; must be positive
     * @param window how many commits to examine; must be positive
     */
    public List<Commit> historyForPath(String revision, String path, int limit, int window) {
        if (limit <= 0) {
            throw new IllegalArgumentException("History limit must be positive");
        }
        if (window <= 0) {
            throw new IllegalArgumentException("History window must be positive");
        }
        if (path == null || path.isBlank()) {
            return history(revision, limit);
        }

        List<Commit> matches = new ArrayList<>();
        for (Commit commit : history(revision, window)) {
            boolean touched = changedPaths(commit).stream()
                    .anyMatch(changed -> touches(changed, path));
            if (touched) {
                matches.add(commit);
                if (matches.size() == limit) {
                    break;
                }
            }
        }
        return List.copyOf(matches);
    }

    private List<String> changedPaths(Commit commit) {
        return changesIn(commit.id()).changes().stream().map(change -> change.path()).toList();
    }

    /** A path is touched by a change to itself, or to anything beneath it. */
    private static boolean touches(String changedPath, String candidate) {
        return changedPath.equals(candidate) || changedPath.startsWith(candidate + "/");
    }

    /** Compares the trees of two revisions. */
    public Optional<TreeDiff> compare(String fromRevision, String toRevision) {
        Optional<ObjectId> from = rootTreeOf(fromRevision);
        Optional<ObjectId> to = rootTreeOf(toRevision);

        if (from.isEmpty() || to.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(differ.diff(from.get(), to.get()));
    }

    private Optional<ObjectId> rootTreeOf(String revision) {
        return resolve(revision).map(commit -> objects.readCommit(commit).tree());
    }

    private Tree readTree(ObjectId treeId) {
        return treeId.equals(EMPTY_TREE_ID) ? Tree.empty() : objects.readTree(treeId);
    }

    private static boolean isRoot(String path) {
        return path == null || path.isBlank() || path.equals("/");
    }
}
