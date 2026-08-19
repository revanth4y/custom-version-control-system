package com.gitforge.vcs.tree;

import com.gitforge.vcs.object.FileMode;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Tree;
import com.gitforge.vcs.object.TreeEntry;
import com.gitforge.vcs.storage.ObjectStore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Reads a stored tree back out.
 *
 * <p>Provides the two operations the rest of the system needs from a Merkle
 * tree: listing what a directory holds, and resolving a path to the object at
 * it. Structural comparison of two trees belongs to the diff phase; what is here
 * is traversal, plus the observation that two states are identical exactly when
 * their root ids match.
 */
public final class TreeWalker {

    private final ObjectStore store;

    public TreeWalker(ObjectStore store) {
        if (store == null) {
            throw new IllegalArgumentException("Object store must not be null");
        }
        this.store = store;
    }

    /** One file, with its full path from the root of the tree that was walked. */
    public record Entry(String path, FileMode mode, ObjectId id) {
    }

    /**
     * Every file beneath {@code rootTreeId}, depth-first, in canonical order.
     *
     * <p>Directories are descended into rather than reported: the result
     * describes the repository as the flat set of paths a checkout would create.
     */
    public List<Entry> flatten(ObjectId rootTreeId) {
        List<Entry> files = new ArrayList<>();
        collect(rootTreeId, "", files);
        return files;
    }

    /** The immediate children of a directory, without descending. */
    public List<TreeEntry> list(ObjectId treeId) {
        return store.readTree(treeId).entries();
    }

    /**
     * Resolves a path against a tree.
     *
     * @return the entry named by {@code path}, or empty if no such path exists
     */
    public Optional<TreeEntry> resolve(ObjectId rootTreeId, String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Path must not be empty");
        }

        Deque<String> segments = new ArrayDeque<>(List.of(path.replace('\\', '/').split("/")));
        ObjectId currentTree = rootTreeId;

        while (!segments.isEmpty()) {
            String segment = segments.removeFirst();
            if (segment.isEmpty()) {
                continue;
            }

            Optional<TreeEntry> match = store.readTree(currentTree).entry(segment);
            if (match.isEmpty()) {
                return Optional.empty();
            }

            TreeEntry entry = match.get();
            if (segments.isEmpty()) {
                return Optional.of(entry);
            }
            if (!entry.isDirectory()) {
                // A path continues past a file, so it cannot exist.
                return Optional.empty();
            }
            currentTree = entry.id();
        }
        return Optional.empty();
    }

    private void collect(ObjectId treeId, String prefix, List<Entry> accumulator) {
        Tree tree = store.readTree(treeId);
        for (TreeEntry entry : tree.entries()) {
            String path = prefix.isEmpty() ? entry.name() : prefix + "/" + entry.name();
            if (entry.isDirectory()) {
                collect(entry.id(), path, accumulator);
            } else {
                accumulator.add(new Entry(path, entry.mode(), entry.id()));
            }
        }
    }
}
