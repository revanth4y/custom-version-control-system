package com.gitforge.vcs.worktree;

import com.gitforge.vcs.object.Blob;
import com.gitforge.vcs.object.FileMode;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.storage.ObjectStore;
import com.gitforge.vcs.tree.TreeWalker;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The materialized form of a tree: ordinary files on disk.
 *
 * <p>This is the only mutable, disposable layer in the system. Objects are
 * immutable and content-addressed; references are mutable but tiny; the working
 * tree is neither authoritative nor durable — it can always be rebuilt from a
 * tree id, and nothing here is ever the source of truth.
 *
 * <p>Lives in its own root directory, separate from the repository metadata, so
 * materialization can never write over {@code objects/} or {@code refs/} and no
 * tree path can reach them.
 */
public final class WorkingTree {

    /** Windows has no POSIX permission view, so the executable bit is simply unavailable. */
    private static final boolean POSIX_SUPPORTED =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private final Path root;
    private final ObjectStore objectStore;
    private final TreeWalker walker;

    public WorkingTree(Path root, ObjectStore objectStore) {
        if (root == null || objectStore == null) {
            throw new IllegalArgumentException("Working tree requires a root directory and an object store");
        }
        this.root = root.toAbsolutePath().normalize();
        this.objectStore = objectStore;
        this.walker = new TreeWalker(objectStore);
        try {
            Files.createDirectories(this.root);
        } catch (IOException ex) {
            throw new WorkingTreeException("Could not create working tree at " + this.root, ex);
        }
    }

    public Path root() {
        return root;
    }

    /**
     * Compares the files on disk against the tree they are supposed to reflect.
     *
     * <p>A tracked file counts as modified when its contents no longer hash to
     * the recorded blob id. That comparison is by content hash rather than by
     * timestamp or size, so it cannot be fooled by a file rewritten with
     * different bytes of the same length.
     *
     * @param treeId the tree the working tree currently reflects
     */
    public WorkingTreeStatus status(ObjectId treeId) {
        Map<String, ObjectId> tracked = trackedFiles(treeId);
        Set<String> onDisk = listFiles();

        List<String> modified = new ArrayList<>();
        List<String> deleted = new ArrayList<>();

        tracked.forEach((path, blobId) -> {
            Path file = resolve(path);
            if (!Files.isRegularFile(file)) {
                deleted.add(path);
                return;
            }
            if (!new Blob(readFile(file)).id().equals(blobId)) {
                modified.add(path);
            }
        });

        List<String> untracked = onDisk.stream()
                .filter(path -> !tracked.containsKey(path))
                .sorted()
                .toList();

        modified.sort(String::compareTo);
        deleted.sort(String::compareTo);
        return new WorkingTreeStatus(modified, deleted, untracked);
    }

    /**
     * Replaces the working tree with the contents of {@code targetTreeId}.
     *
     * <p>Files tracked by {@code currentTreeId} that the target does not contain
     * are removed, along with any directories left empty. Untracked files are
     * left alone: they were never ours to delete.
     *
     * <p>Callers are expected to have checked {@link #status} first;
     * {@link CheckoutService} does exactly that.
     */
    public void materialize(ObjectId targetTreeId, ObjectId currentTreeId) {
        Map<String, ObjectId> target = trackedFiles(targetTreeId);
        Map<String, ObjectId> current = currentTreeId == null ? Map.of() : trackedFiles(currentTreeId);

        for (String path : current.keySet()) {
            if (!target.containsKey(path)) {
                removeFile(resolve(path));
            }
        }
        for (TreeWalker.Entry entry : walker.flatten(targetTreeId)) {
            writeFile(entry);
        }
        pruneEmptyDirectories();
    }

    /** Every file path the tree describes, mapped to its blob id. */
    public Map<String, ObjectId> trackedFiles(ObjectId treeId) {
        Map<String, ObjectId> tracked = new LinkedHashMap<>();
        for (TreeWalker.Entry entry : walker.flatten(treeId)) {
            tracked.put(entry.path(), entry.id());
        }
        return tracked;
    }

    /** Every file currently on disk, as repository-relative paths. */
    public Set<String> listFiles() {
        if (!Files.isDirectory(root)) {
            return Set.of();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        } catch (IOException ex) {
            throw new WorkingTreeException("Could not scan working tree at " + root, ex);
        }
    }

    private void writeFile(TreeWalker.Entry entry) {
        Path file = resolve(entry.path());
        Blob blob = objectStore.readBlob(entry.id());
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, blob.payload());
            applyExecutableBit(file, entry.mode());
        } catch (IOException ex) {
            throw new WorkingTreeException("Could not write " + entry.path(), ex);
        }
    }

    /**
     * Applies the executable bit where the platform supports it.
     *
     * <p>Skipped on filesystems without POSIX permissions. The mode is recorded
     * in the tree object either way, so object identity and hashes are identical
     * across platforms — only the materialized file differs.
     */
    private static void applyExecutableBit(Path file, FileMode mode) throws IOException {
        if (!POSIX_SUPPORTED) {
            return;
        }
        Set<PosixFilePermission> permissions = new HashSet<>(Files.getPosixFilePermissions(file));
        if (mode == FileMode.EXECUTABLE_FILE) {
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
        } else {
            permissions.remove(PosixFilePermission.OWNER_EXECUTE);
            permissions.remove(PosixFilePermission.GROUP_EXECUTE);
            permissions.remove(PosixFilePermission.OTHERS_EXECUTE);
        }
        Files.setPosixFilePermissions(file, permissions);
    }

    private void removeFile(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            throw new WorkingTreeException("Could not remove " + root.relativize(file), ex);
        }
    }

    private void pruneEmptyDirectories() {
        try (Stream<Path> paths = Files.walk(root)) {
            // Deepest first, so a directory containing only empty directories
            // also becomes empty and is removed in the same pass.
            List<Path> directories = paths
                    .filter(Files::isDirectory)
                    .filter(path -> !path.equals(root))
                    .sorted(java.util.Comparator.comparingInt(Path::getNameCount).reversed())
                    .toList();

            for (Path directory : directories) {
                try (Stream<Path> entries = Files.list(directory)) {
                    if (entries.findAny().isEmpty()) {
                        Files.delete(directory);
                    }
                }
            }
        } catch (IOException ex) {
            throw new WorkingTreeException("Could not prune empty directories under " + root, ex);
        }
    }

    private byte[] readFile(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException ex) {
            throw new WorkingTreeException("Could not read " + root.relativize(file), ex);
        }
    }

    /**
     * Resolves a repository-relative path, refusing anything that escapes the
     * working tree.
     *
     * <p>Tree entry names are already validated, so this should be unreachable;
     * it is a second, independent barrier because the consequence of being wrong
     * is writing arbitrary files.
     */
    private Path resolve(String path) {
        Path resolved = root.resolve(path).normalize();
        if (!resolved.startsWith(root)) {
            throw new WorkingTreeException("Path escapes the working tree: " + path);
        }
        return resolved;
    }
}
