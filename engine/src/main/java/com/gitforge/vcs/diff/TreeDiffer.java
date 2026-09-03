package com.gitforge.vcs.diff;

import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Tree;
import com.gitforge.vcs.object.TreeEntry;
import com.gitforge.vcs.storage.ObjectStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Structural comparison of two trees.
 *
 * <p>Walks both trees in parallel and stops descending the moment two subtrees
 * are known to be identical. A tree's id is a hash over its whole contents,
 * transitively, so equal ids mean equal subtrees — not probably equal, but
 * equal, since a false match would require a SHA-1 collision. A directory
 * untouched between two commits therefore costs zero object reads: neither the
 * subtree nor any blob beneath it is ever loaded.
 *
 * <p>That is why this deliberately does not use
 * {@link com.gitforge.vcs.tree.TreeWalker#flatten}, which reads every object
 * beneath a tree and would defeat the entire point. Cost here is proportional to
 * what changed, not to the size of the repository.
 */
public final class TreeDiffer {

    /**
     * The empty tree has fixed, known contents, so it can be treated as readable
     * whether or not it was ever written.
     */
    private static final ObjectId EMPTY_TREE_ID = Tree.empty().id();

    private final ObjectStore store;

    public TreeDiffer(ObjectStore store) {
        if (store == null) {
            throw new IllegalArgumentException("Object store must not be null");
        }
        this.store = store;
    }

    /**
     * Compares two trees.
     *
     * @return every file-level difference, sorted by path
     */
    public TreeDiff diff(ObjectId oldTree, ObjectId newTree) {
        if (oldTree == null || newTree == null) {
            throw new IllegalArgumentException("Both trees are required");
        }
        List<TreeChange> changes = new ArrayList<>();
        compare("", oldTree, newTree, changes);

        changes.sort(Comparator.comparing(TreeChange::path));
        return new TreeDiff(changes);
    }

    private void compare(String prefix, ObjectId oldTree, ObjectId newTree, List<TreeChange> changes) {
        // The whole-subtree short circuit. Everything below is provably
        // identical, so nothing here is read.
        if (oldTree.equals(newTree)) {
            return;
        }

        Map<String, TreeEntry> oldEntries = entriesOf(oldTree);
        Map<String, TreeEntry> newEntries = entriesOf(newTree);

        Set<String> names = new TreeSet<>(oldEntries.keySet());
        names.addAll(newEntries.keySet());

        for (String name : names) {
            TreeEntry before = oldEntries.get(name);
            TreeEntry after = newEntries.get(name);
            String path = join(prefix, name);

            if (before == null) {
                collect(path, after, changes, true);
            } else if (after == null) {
                collect(path, before, changes, false);
            } else if (before.equals(after)) {
                // Same mode and same id: this entry, and everything under it if
                // it is a directory, is unchanged.
                continue;
            } else if (before.isDirectory() && after.isDirectory()) {
                compare(path, before.id(), after.id(), changes);
            } else if (!before.isDirectory() && !after.isDirectory()) {
                changes.add(new TreeChange.Modified(
                        path, before.mode(), before.id(), after.mode(), after.id()));
            } else {
                // A file became a directory or the reverse. Reported at file
                // level: the old path disappears and the new files arrive.
                collect(path, before, changes, false);
                collect(path, after, changes, true);
            }
        }
    }

    /** Records an entry, expanding a directory into the files beneath it. */
    private void collect(String path, TreeEntry entry, List<TreeChange> changes, boolean added) {
        if (!entry.isDirectory()) {
            changes.add(added
                    ? new TreeChange.Added(path, entry.mode(), entry.id())
                    : new TreeChange.Deleted(path, entry.mode(), entry.id()));
            return;
        }
        for (TreeEntry child : entriesOf(entry.id()).values()) {
            collect(join(path, child.name()), child, changes, added);
        }
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

    private static String join(String prefix, String name) {
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }
}
