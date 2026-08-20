package com.gitforge.vcs.merge;

import com.gitforge.vcs.diff.TreeChange;
import com.gitforge.vcs.diff.TreeDiffer;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Tree;
import com.gitforge.vcs.object.TreeEntry;
import com.gitforge.vcs.storage.ObjectStore;
import com.gitforge.vcs.tree.TreeWalker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Three-way merge of two trees against their common base.
 *
 * <p>A pure function of three tree ids. It knows nothing of commits, branches,
 * merge bases or the working tree: locating the base and recording the result as
 * a merge commit belong to the layer above, which keeps this testable as
 * straightforward input and output.
 *
 * <p>Merging is a parallel walk of all three trees rather than a comparison of
 * two diffs. Walking together keeps base, ours and theirs in hand at every node,
 * which is exactly what each decision needs; reconstructing that from two
 * separate diffs afterwards would be strictly harder.
 *
 * <p>The same three short circuits apply at every level, in this order:
 *
 * <ol>
 *   <li>{@code ours == theirs} — both sides agree, including when both made the
 *       same change. Tested first, which is what makes identical edits a clean
 *       merge rather than a conflict.</li>
 *   <li>{@code ours == base} — only they touched this; take theirs.</li>
 *   <li>{@code theirs == base} — only we touched this; take ours.</li>
 * </ol>
 *
 * <p>Because tree ids hash their contents transitively, each of these skips an
 * entire subtree without reading a single object beneath it, and the untouched
 * subtree's existing id is reused verbatim in the merged result — so no new
 * objects are written for parts of the repository nobody changed.
 */
public final class ThreeWayMerger {

    /** Known contents, so it needs no read and need not have been stored. */
    private static final ObjectId EMPTY_TREE_ID = Tree.empty().id();

    private final ObjectStore store;
    private final TreeDiffer differ;
    private final TreeWalker walker;

    public ThreeWayMerger(ObjectStore store) {
        if (store == null) {
            throw new IllegalArgumentException("Object store must not be null");
        }
        this.store = store;
        this.differ = new TreeDiffer(store);
        this.walker = new TreeWalker(store);
    }

    /**
     * Merges {@code ours} and {@code theirs} using {@code base} as their common
     * ancestor state.
     *
     * @return a {@link MergeResult.Clean} carrying the merged tree, or a
     *     {@link MergeResult.Conflicted} carrying the unresolved paths
     */
    public MergeResult merge(ObjectId base, ObjectId ours, ObjectId theirs) {
        if (base == null || ours == null || theirs == null) {
            throw new IllegalArgumentException("Merging requires a base, ours and theirs");
        }

        // Whole-merge fast paths, mirroring the per-node rules below.
        if (ours.equals(theirs)) {
            return new MergeResult.Clean(ours);
        }
        if (ours.equals(base)) {
            return new MergeResult.Clean(theirs);
        }
        if (theirs.equals(base)) {
            return new MergeResult.Clean(ours);
        }

        Run run = new Run();
        ObjectId merged = run.mergeTree("", base, ours, theirs);

        if (!run.conflicts.isEmpty()) {
            run.conflicts.sort(Comparator.comparing(MergeConflict::path));
            run.cleanChanges.sort(Comparator.comparing(TreeChange::path));
            // Nothing is written: the trees built along the way describe a state
            // that was never actually resolved.
            return new MergeResult.Conflicted(run.conflicts, run.cleanChanges);
        }

        run.writePendingTrees();
        return new MergeResult.Clean(merged);
    }

    /**
     * State for one merge.
     *
     * <p>Newly built trees are held in memory and written only if the merge turns
     * out to be clean. {@link Tree} computes its own id on construction, so the
     * result can be assembled and identified in full before anything is
     * persisted — which is how a conflicted merge leaves the object store
     * untouched.
     */
    private final class Run {

        private final List<MergeConflict> conflicts = new ArrayList<>();
        private final List<TreeChange> cleanChanges = new ArrayList<>();
        private final List<Tree> pendingTrees = new ArrayList<>();

        private ObjectId mergeTree(String prefix, ObjectId base, ObjectId ours, ObjectId theirs) {
            if (ours.equals(theirs)) {
                return ours;
            }
            if (ours.equals(base)) {
                recordTaken(prefix, ours, theirs);
                return theirs;
            }
            if (theirs.equals(base)) {
                return ours;
            }

            Map<String, TreeEntry> baseEntries = entriesOf(base);
            Map<String, TreeEntry> ourEntries = entriesOf(ours);
            Map<String, TreeEntry> theirEntries = entriesOf(theirs);

            Set<String> names = new TreeSet<>(baseEntries.keySet());
            names.addAll(ourEntries.keySet());
            names.addAll(theirEntries.keySet());

            List<TreeEntry> merged = new ArrayList<>();
            for (String name : names) {
                resolve(prefix, name, baseEntries.get(name), ourEntries.get(name), theirEntries.get(name))
                        .ifPresent(merged::add);
            }

            // Tree canonicalises entry order itself, so the order names were
            // visited in cannot influence the resulting id.
            Tree tree = new Tree(merged);
            pendingTrees.add(tree);
            return tree.id();
        }

        /** Decides a single name from the three sides. */
        private java.util.Optional<TreeEntry> resolve(
                String prefix, String name, TreeEntry base, TreeEntry ours, TreeEntry theirs) {

            String path = join(prefix, name);

            if (Objects.equals(ours, theirs)) {
                return java.util.Optional.ofNullable(ours);
            }
            if (Objects.equals(ours, base)) {
                recordEntryTaken(path, ours, theirs);
                return java.util.Optional.ofNullable(theirs);
            }
            if (Objects.equals(theirs, base)) {
                return java.util.Optional.ofNullable(ours);
            }

            // Both sides changed this name in different ways.
            if (isDirectory(ours) && isDirectory(theirs)) {
                // Not a conflict, just a smaller instance of the same problem.
                // When the base held something other than a directory here, both
                // sides independently created one, so they merge against nothing.
                ObjectId baseSubtree = isDirectory(base) ? base.id() : EMPTY_TREE_ID;
                ObjectId subtree = mergeTree(path, baseSubtree, ours.id(), theirs.id());

                // A directory whose contents all vanished is not represented at
                // all, matching how trees describe directories by their contents.
                return subtree.equals(EMPTY_TREE_ID)
                        ? java.util.Optional.empty()
                        : java.util.Optional.of(new TreeEntry(
                                com.gitforge.vcs.object.FileMode.DIRECTORY, name, subtree));
            }

            conflicts.add(MergeConflict.of(classify(base, ours, theirs), path, base, ours, theirs));
            // Ours stands in while the walk continues so every conflict is found
            // in one pass rather than stopping at the first. The tree built from
            // it is discarded.
            return java.util.Optional.ofNullable(ours);
        }

        /** Records what taking a whole subtree from theirs changes relative to ours. */
        private void recordTaken(String prefix, ObjectId ours, ObjectId theirs) {
            for (TreeChange change : differ.diff(ours, theirs).changes()) {
                cleanChanges.add(withPrefix(prefix, change));
            }
        }

        /** Records what taking a single entry from theirs changes relative to ours. */
        private void recordEntryTaken(String path, TreeEntry ours, TreeEntry theirs) {
            if (ours != null && theirs != null && ours.isDirectory() && theirs.isDirectory()) {
                for (TreeChange change : differ.diff(ours.id(), theirs.id()).changes()) {
                    cleanChanges.add(withPrefix(path, change));
                }
                return;
            }
            if (ours != null) {
                if (theirs != null && !ours.isDirectory() && !theirs.isDirectory()) {
                    cleanChanges.add(new TreeChange.Modified(
                            path, ours.mode(), ours.id(), theirs.mode(), theirs.id()));
                    return;
                }
                expand(path, ours, false);
            }
            if (theirs != null) {
                expand(path, theirs, true);
            }
        }

        /** Emits an entry as file-level changes, descending into directories. */
        private void expand(String path, TreeEntry entry, boolean added) {
            if (!entry.isDirectory()) {
                cleanChanges.add(added
                        ? new TreeChange.Added(path, entry.mode(), entry.id())
                        : new TreeChange.Deleted(path, entry.mode(), entry.id()));
                return;
            }
            for (TreeWalker.Entry file : walker.flatten(entry.id())) {
                String filePath = join(path, file.path());
                cleanChanges.add(added
                        ? new TreeChange.Added(filePath, file.mode(), file.id())
                        : new TreeChange.Deleted(filePath, file.mode(), file.id()));
            }
        }

        private void writePendingTrees() {
            pendingTrees.forEach(store::write);
        }

        private Map<String, TreeEntry> entriesOf(ObjectId treeId) {
            if (treeId.equals(EMPTY_TREE_ID)) {
                return Map.of();
            }
            Map<String, TreeEntry> byName = new LinkedHashMap<>();
            for (TreeEntry entry : store.readTree(treeId).entries()) {
                byName.put(entry.name(), entry);
            }
            return byName;
        }
    }

    /** Names the disagreement, given what each side had. */
    private static ConflictKind classify(TreeEntry base, TreeEntry ours, TreeEntry theirs) {
        if (isDirectory(ours) || isDirectory(theirs)) {
            return ConflictKind.TYPE;
        }
        if (ours == null || theirs == null) {
            return ConflictKind.MODIFY_DELETE;
        }
        if (ours.id().equals(theirs.id())) {
            // Same bytes, so the only thing left to disagree about is the mode.
            return ConflictKind.MODE;
        }
        return base == null ? ConflictKind.ADD_ADD : ConflictKind.CONTENT;
    }

    private static boolean isDirectory(TreeEntry entry) {
        return entry != null && entry.isDirectory();
    }

    private static TreeChange withPrefix(String prefix, TreeChange change) {
        String path = join(prefix, change.path());
        return switch (change) {
            case TreeChange.Added added -> new TreeChange.Added(path, added.mode(), added.blob());
            case TreeChange.Deleted deleted -> new TreeChange.Deleted(path, deleted.mode(), deleted.blob());
            case TreeChange.Modified modified -> new TreeChange.Modified(
                    path, modified.oldMode(), modified.oldBlob(), modified.newMode(), modified.newBlob());
        };
    }

    private static String join(String prefix, String name) {
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }
}
