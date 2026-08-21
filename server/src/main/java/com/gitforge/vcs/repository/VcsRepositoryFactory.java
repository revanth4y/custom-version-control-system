package com.gitforge.vcs.repository;

import com.gitforge.vcs.ref.FileSystemRefStore;
import com.gitforge.vcs.ref.Head;
import com.gitforge.vcs.ref.RefStore;
import com.gitforge.vcs.storage.FileSystemObjectStore;
import com.gitforge.vcs.storage.ObjectStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates and opens repositories beneath a single storage root.
 *
 * <p>The only place a {@link RepositoryId} becomes a filesystem path, which is
 * what makes repository isolation structural rather than a rule every call site
 * has to remember. Each repository gets its own object store and reference
 * store:
 *
 * <pre>
 *   storage/&lt;repositoryId&gt;/
 *       objects/
 *       refs/heads/
 *       HEAD
 * </pre>
 *
 * <p>There is no shared store and no path from one repository's services to
 * another's storage. Two repositories holding the same file will compute the
 * same object id — content addressing is global by nature — but each keeps its
 * own copy, and neither can read the other's.
 */
public final class VcsRepositoryFactory {

    private final Path storageRoot;

    public VcsRepositoryFactory(Path storageRoot) {
        if (storageRoot == null) {
            throw new IllegalArgumentException("Storage root must not be null");
        }
        this.storageRoot = storageRoot.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.storageRoot);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not create storage root at " + this.storageRoot, ex);
        }
    }

    /**
     * Prepares storage for a new repository and points HEAD at its default
     * branch.
     *
     * <p>The branch itself does not exist yet: it comes into being with the first
     * commit, which is exactly how an empty repository behaves.
     *
     * @throws IllegalStateException if the repository already exists
     */
    public VcsRepository initialise(RepositoryId id, String defaultBranch) {
        if (exists(id)) {
            throw new IllegalStateException("Repository already exists: " + id);
        }
        VcsRepository repository = create(id);
        repository.refs().setHead(Head.onBranch(defaultBranch));
        return repository;
    }

    /**
     * Opens an existing repository.
     *
     * @throws IllegalStateException if it has not been initialised
     */
    public VcsRepository open(RepositoryId id) {
        if (!exists(id)) {
            throw new IllegalStateException("Repository does not exist: " + id);
        }
        return create(id);
    }

    /** Opens a repository, initialising it first if it is not there yet. */
    public VcsRepository openOrInitialise(RepositoryId id, String defaultBranch) {
        return exists(id) ? open(id) : initialise(id, defaultBranch);
    }

    public boolean exists(RepositoryId id) {
        return Files.isRegularFile(pathFor(id).resolve("HEAD"));
    }

    /** The directory holding one repository's storage. */
    public Path pathFor(RepositoryId id) {
        if (id == null) {
            throw new IllegalArgumentException("Repository id must not be null");
        }
        Path resolved = storageRoot.resolve(id.value()).normalize();

        // Independent of the validation in RepositoryId: a naming rule that
        // turns out to be incomplete still cannot reach outside the storage
        // root, or into another repository's directory.
        if (!resolved.startsWith(storageRoot) || resolved.equals(storageRoot)) {
            throw new IllegalArgumentException("Repository id escapes the storage root: " + id);
        }
        return resolved;
    }

    public Path storageRoot() {
        return storageRoot;
    }

    private VcsRepository create(RepositoryId id) {
        Path root = pathFor(id);
        ObjectStore objects = new FileSystemObjectStore(root);
        RefStore refs = new FileSystemRefStore(root);
        return new VcsRepository(id, objects, refs);
    }
}
