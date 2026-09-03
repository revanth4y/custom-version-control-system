package com.gitforge.vcs.tree;

import com.gitforge.vcs.object.FileMode;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Tree;
import com.gitforge.vcs.object.TreeEntry;
import com.gitforge.vcs.storage.ObjectStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Applies path updates to a tree, rebuilding only the directories they touch.
 *
 * <p>{@link TreeBuilder} is the right tool for constructing a tree from a
 * complete list of paths, but the wrong one for changing an existing tree: it
 * would have to enumerate every file in the repository to alter one of them.
 * This walks down only the directories named by the updates. Any entry no update
 * mentions is copied across <em>by id</em>, so its subtree is never read and no
 * object is written for it.
 *
 * <p>Editing one file in a repository of a thousand therefore rewrites only the
 * directories along that single path, and every other subtree keeps the exact
 * object it already had.
 *
 * <p>Updates are strict rather than forgiving: removing a path that is not there,
 * or writing a file over a directory, is an error. An API that quietly ignores
 * such a request hides the caller's bug instead of reporting it.
 */
public final class TreeUpdater {

    /** Known contents, so it needs no read and need not have been stored. */
    private static final ObjectId EMPTY_TREE_ID = Tree.empty().id();

    private final ObjectStore store;

    public TreeUpdater(ObjectStore store) {
        if (store == null) {
            throw new IllegalArgumentException("Object store must not be null");
        }
        this.store = store;
    }

    /**
     * Returns the tree that results from applying {@code updates} to
     * {@code baseTree}.
     *
     * <p>The base tree is unchanged: trees are immutable, so this produces a new
     * one that shares every untouched subtree with the original.
     *
     * @return the new root tree id, equal to {@code baseTree} if the updates
     *     turn out to change nothing
     */
    public ObjectId apply(ObjectId baseTree, List<PathUpdate> updates) {
        if (baseTree == null) {
            throw new IllegalArgumentException("A base tree is required");
        }
        if (updates == null) {
            throw new IllegalArgumentException("Updates must not be null");
        }
        if (updates.isEmpty()) {
            return baseTree;
        }

        Directory root = new Directory();
        for (PathUpdate update : updates) {
            root.insert(segments(update.path()), 0, update);
        }
        return applyTo("", baseTree, root);
    }

    /**
     * Rebuilds one directory.
     *
     * @param pending the updates that fall inside this directory, already grouped
     */
    private ObjectId applyTo(String prefix, ObjectId treeId, Directory pending) {
        Map<String, TreeEntry> existing = entriesOf(treeId);
        List<TreeEntry> result = new ArrayList<>();

        for (TreeEntry entry : existing.values()) {
            boolean touched = pending.files.containsKey(entry.name())
                    || pending.subdirectories.containsKey(entry.name());
            if (!touched) {
                // Nothing below this name is changing, so it is carried over by
                // id. Its subtree is never read and nothing is rewritten for it.
                result.add(entry);
            }
        }

        for (Map.Entry<String, PathUpdate> file : pending.files.entrySet()) {
            String name = file.getKey();
            TreeEntry current = existing.get(name);
            String path = join(prefix, name);

            if (current != null && current.isDirectory()) {
                throw new IllegalArgumentException("Path is a directory, not a file: " + path);
            }
            switch (file.getValue()) {
                case PathUpdate.Put put -> result.add(new TreeEntry(put.mode(), name, put.blob()));
                case PathUpdate.Remove ignored -> {
                    if (current == null) {
                        throw new IllegalArgumentException("Cannot remove a path that does not exist: " + path);
                    }
                    // Emitting nothing is the removal.
                }
            }
        }

        for (Map.Entry<String, Directory> child : pending.subdirectories.entrySet()) {
            String name = child.getKey();
            TreeEntry current = existing.get(name);
            String path = join(prefix, name);

            if (current != null && !current.isDirectory()) {
                throw new IllegalArgumentException("Path is a file, not a directory: " + path);
            }
            ObjectId childBase = current == null ? EMPTY_TREE_ID : current.id();
            ObjectId updated = applyTo(path, childBase, child.getValue());

            // A directory emptied by its removals is not represented at all: a
            // tree describes directories only through their contents.
            if (!updated.equals(EMPTY_TREE_ID)) {
                result.add(new TreeEntry(FileMode.DIRECTORY, name, updated));
            }
        }

        // Tree canonicalises entry order, so the order names were visited in
        // cannot influence the resulting id.
        return store.write(new Tree(result));
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

    private static String[] segments(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Path must not be empty");
        }
        String normalised = path.replace('\\', '/');
        if (normalised.startsWith("/")) {
            throw new IllegalArgumentException("Path must be relative to the repository root: " + path);
        }
        String[] parts = normalised.split("/", -1);
        for (String part : parts) {
            if (part.isEmpty()) {
                throw new IllegalArgumentException("Path must not contain empty segments: " + path);
            }
            if (part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("Path must not contain '.' or '..' segments: " + path);
            }
        }
        return parts;
    }

    private static String join(String prefix, String name) {
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }

    /** Updates grouped by the directory they apply to. */
    private static final class Directory {

        private final Map<String, Directory> subdirectories = new TreeMap<>();
        private final Map<String, PathUpdate> files = new TreeMap<>();

        void insert(String[] path, int depth, PathUpdate update) {
            String segment = path[depth];
            boolean isLast = depth == path.length - 1;

            if (isLast) {
                if (subdirectories.containsKey(segment)) {
                    throw new IllegalArgumentException(
                            "Path is used as both a file and a directory: " + update.path());
                }
                files.put(segment, update);
                return;
            }
            if (files.containsKey(segment)) {
                throw new IllegalArgumentException(
                        "Path is used as both a file and a directory: " + update.path());
            }
            subdirectories.computeIfAbsent(segment, ignored -> new Directory())
                    .insert(path, depth + 1, update);
        }
    }
}
