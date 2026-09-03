package com.gitforge.vcs.repository;

import com.gitforge.vcs.diff.FileDiff;
import com.gitforge.vcs.diff.Hunk;
import com.gitforge.vcs.diff.InlineDiffer;
import com.gitforge.vcs.diff.LineDiffer;
import com.gitforge.vcs.diff.TreeChange;
import com.gitforge.vcs.diff.TreeDiffer;
import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.FileMode;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.TextContent;
import com.gitforge.vcs.object.Tree;
import com.gitforge.vcs.storage.ObjectStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Line-level differences between two repository states.
 *
 * <p>Joins the two halves of diffing: {@link TreeDiffer} finds <em>which</em>
 * files changed, cheaply, by skipping subtrees whose hashes match; then
 * {@link LineDiffer} works out <em>how</em> each one changed. Because the
 * structural pass short-circuits first, the expensive line diff only ever runs
 * on files that genuinely differ.
 *
 * <p>Kept separate from {@link RepositoryReader} so neither becomes a catch-all:
 * reading answers "what is there", this answers "what changed".
 */
public final class DiffService {

    private static final ObjectId EMPTY_TREE_ID = Tree.empty().id();

    /**
     * The most files given line-level hunks in one response.
     *
     * <p>A sweeping change can touch thousands of files, and nobody reads
     * thousands of diffs at once. Beyond this the structural summary is still
     * returned, so nothing is hidden — only the detail is deferred.
     */
    static final int MAX_FILES_WITH_HUNKS = 100;

    private final ObjectStore objects;
    private final TreeDiffer treeDiffer;

    DiffService(ObjectStore objects) {
        this.objects = objects;
        this.treeDiffer = new TreeDiffer(objects);
    }

    /** Differences between two trees, with hunks for text files that changed. */
    public List<FileDiff> diffTrees(ObjectId oldTree, ObjectId newTree, String pathFilter) {
        List<TreeChange> changes = treeDiffer.diff(oldTree, newTree).changes().stream()
                .filter(change -> pathFilter == null || pathFilter.isBlank()
                        || change.path().equals(pathFilter))
                .toList();

        List<FileDiff> diffs = new ArrayList<>(changes.size());
        int withHunks = 0;

        // One differ for the whole comparison, because its budget is a property
        // of the response rather than of any single file.
        InlineDiffer inlineDiffer = new InlineDiffer();

        for (TreeChange change : changes) {
            boolean allowHunks = withHunks < MAX_FILES_WITH_HUNKS;
            FileDiff diff = toFileDiff(change, allowHunks, inlineDiffer);
            if (diff.hasHunks()) {
                withHunks++;
            }
            diffs.add(diff);
        }
        return diffs;
    }

    /**
     * What a commit changed, against its first parent.
     *
     * <p>An initial commit is compared with the empty tree, so its files appear
     * as additions rather than requiring the caller to special-case it. For a
     * merge only the first parent is used: the second parent's work is by
     * definition already on the branch being merged into.
     */
    public List<FileDiff> diffCommit(ObjectId commitId, String pathFilter) {
        Commit commit = objects.readCommit(commitId);
        ObjectId parentTree = commit.parents().isEmpty()
                ? EMPTY_TREE_ID
                : objects.readCommit(commit.parents().getFirst()).tree();

        return diffTrees(parentTree, commit.tree(), pathFilter);
    }

    private FileDiff toFileDiff(TreeChange change, boolean allowHunks, InlineDiffer inlineDiffer) {
        return switch (change) {
            case TreeChange.Added added -> build(
                    added.path(), FileDiff.Status.ADDED,
                    null, added.blob(), null, added.mode(), allowHunks, inlineDiffer);

            case TreeChange.Deleted deleted -> build(
                    deleted.path(), FileDiff.Status.DELETED,
                    deleted.blob(), null, deleted.mode(), null, allowHunks, inlineDiffer);

            case TreeChange.Modified modified -> build(
                    modified.path(), FileDiff.Status.MODIFIED,
                    modified.oldBlob(), modified.newBlob(),
                    modified.oldMode(), modified.newMode(), allowHunks, inlineDiffer);
        };
    }

    private FileDiff build(
            String path,
            FileDiff.Status status,
            ObjectId oldBlob,
            ObjectId newBlob,
            FileMode oldMode,
            FileMode newMode,
            boolean allowHunks,
            InlineDiffer inlineDiffer) {

        byte[] oldContent = oldBlob == null ? new byte[0] : objects.readBlob(oldBlob).payload();
        byte[] newContent = newBlob == null ? new byte[0] : objects.readBlob(newBlob).payload();

        Optional<String> oldText = TextContent.asText(oldContent);
        Optional<String> newText = TextContent.asText(newContent);

        if (oldText.isEmpty() || newText.isEmpty()) {
            // Line numbers mean nothing in a binary file; report the change
            // without pretending it can be read as lines.
            return new FileDiff(path, status, oldBlob, newBlob, oldMode, newMode,
                    true, false, List.of(), 0, 0, oldContent.length, newContent.length);
        }
        if (!allowHunks) {
            return new FileDiff(path, status, oldBlob, newBlob, oldMode, newMode,
                    false, true, List.of(), 0, 0, oldContent.length, newContent.length);
        }

        Optional<List<Hunk>> hunks = LineDiffer.diff(oldText.get(), newText.get());
        if (hunks.isEmpty()) {
            return new FileDiff(path, status, oldBlob, newBlob, oldMode, newMode,
                    false, true, List.of(), 0, 0, oldContent.length, newContent.length);
        }

        // After line diffing, never instead of it: which lines changed is
        // already decided, and this only marks what changed inside them.
        List<Hunk> annotated = inlineDiffer.annotate(hunks.get());

        int additions = 0;
        int deletions = 0;
        for (Hunk hunk : annotated) {
            for (var line : hunk.lines()) {
                switch (line.type()) {
                    case ADDED -> additions++;
                    case REMOVED -> deletions++;
                    case CONTEXT -> {
                        // Context lines are neither added nor removed.
                    }
                }
            }
        }
        return new FileDiff(path, status, oldBlob, newBlob, oldMode, newMode,
                false, false, annotated, additions, deletions, oldContent.length, newContent.length);
    }
}
