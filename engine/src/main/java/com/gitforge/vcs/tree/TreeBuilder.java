package com.gitforge.vcs.tree;

import com.gitforge.vcs.object.Blob;
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
 * Turns a flat set of file paths into the nested trees that represent them, and
 * returns the Merkle root.
 *
 * <p>Callers describe the repository as paths — {@code src/App.java},
 * {@code README.md} — and this class derives the directory objects. Each
 * directory becomes a {@link Tree} whose entries carry its children's ids, so
 * building proceeds bottom-up: a tree cannot be hashed until every child beneath
 * it has been.
 *
 * <p>The returned root id is the Merkle root. Two repositories with identical
 * contents produce the same root regardless of the order files were added, and
 * altering any single file changes the root.
 *
 * <p>Instances are single-use scratch space and are not thread-safe.
 */
public final class TreeBuilder {

    private final ObjectStore store;

    /** Files keyed by full path; insertion order is irrelevant to the result. */
    private final Map<String, FileRef> files = new LinkedHashMap<>();

    public TreeBuilder(ObjectStore store) {
        if (store == null) {
            throw new IllegalArgumentException("Object store must not be null");
        }
        this.store = store;
    }

    /** Records a file whose blob has already been stored. */
    public TreeBuilder add(String path, FileMode mode, ObjectId blobId) {
        if (mode == null || mode.isDirectory()) {
            throw new IllegalArgumentException("A file entry requires a non-directory mode");
        }
        if (blobId == null) {
            throw new IllegalArgumentException("Blob id must not be null");
        }
        files.put(normalise(path), new FileRef(mode, blobId));
        return this;
    }

    /** Stores {@code content} as a blob and records it at {@code path}. */
    public TreeBuilder addFile(String path, byte[] content) {
        return addFile(path, content, FileMode.REGULAR_FILE);
    }

    public TreeBuilder addFile(String path, byte[] content, FileMode mode) {
        ObjectId blobId = store.write(new Blob(content));
        return add(path, mode, blobId);
    }

    /**
     * Writes every tree implied by the recorded files and returns the root id.
     *
     * <p>An empty builder yields the empty tree, which is a legitimate object
     * with a stable id rather than a special case.
     */
    public ObjectId build() {
        Directory root = new Directory();
        for (Map.Entry<String, FileRef> file : files.entrySet()) {
            root.insert(splitPath(file.getKey()), 0, file.getValue());
        }
        return writeDirectory(root);
    }

    /** Depth-first: children are written before the parent that names them. */
    private ObjectId writeDirectory(Directory directory) {
        List<TreeEntry> entries = new ArrayList<>();

        directory.files.forEach((name, file) ->
                entries.add(new TreeEntry(file.mode(), name, file.blobId())));

        directory.subdirectories.forEach((name, child) ->
                entries.add(new TreeEntry(FileMode.DIRECTORY, name, writeDirectory(child))));

        return store.write(new Tree(entries));
    }

    private static String normalise(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Path must not be empty");
        }
        String trimmed = path.replace('\\', '/');
        if (trimmed.startsWith("/")) {
            throw new IllegalArgumentException("Path must be relative to the repository root: " + path);
        }
        for (String segment : trimmed.split("/", -1)) {
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("Path must not contain empty segments: " + path);
            }
            // Rejected here rather than at checkout: a path escaping the root
            // would let a crafted tree write outside the repository.
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Path must not contain '.' or '..' segments: " + path);
            }
        }
        return trimmed;
    }

    private static String[] splitPath(String path) {
        return path.split("/");
    }

    private record FileRef(FileMode mode, ObjectId blobId) {
    }

    /**
     * A directory under construction.
     *
     * <p>Children are kept in {@link TreeMap}s so traversal is deterministic;
     * final canonical ordering is applied by {@link Tree} itself.
     */
    private static final class Directory {

        private final Map<String, Directory> subdirectories = new TreeMap<>();
        private final Map<String, FileRef> files = new TreeMap<>();

        void insert(String[] segments, int depth, FileRef file) {
            String segment = segments[depth];
            boolean isLast = depth == segments.length - 1;

            if (isLast) {
                if (subdirectories.containsKey(segment)) {
                    throw new IllegalArgumentException(
                            "Path is already used by a directory: " + String.join("/", segments));
                }
                files.put(segment, file);
                return;
            }

            if (files.containsKey(segment)) {
                throw new IllegalArgumentException(
                        "Path is already used by a file: " + String.join("/", segments));
            }
            subdirectories.computeIfAbsent(segment, unused -> new Directory())
                    .insert(segments, depth + 1, file);
        }
    }
}
