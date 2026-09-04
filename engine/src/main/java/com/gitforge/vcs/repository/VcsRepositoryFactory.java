package com.gitforge.vcs.repository;

import com.gitforge.vcs.ref.FileSystemRefStore;
import com.gitforge.vcs.ref.Head;
import com.gitforge.vcs.ref.RefStore;
import com.gitforge.vcs.storage.FileSystemObjectStore;
import com.gitforge.vcs.storage.ObjectStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * How many repositories keep their object store between opens.
     *
     * <p>Small on purpose. A cached store holds the repository's verified-object
     * cache, which is bounded at 4,096 entries of at most 64 KiB, so the memory
     * this can retain is this number multiplied by that bound. Measured on
     * five-hundred-commit repositories a warmed store retains about 0.3 MB, so
     * eight of them cost a few megabytes; the arithmetic worst case, a
     * repository whose recent working set is thousands of blobs just under the
     * payload cap, is 256 MB each. Eight keeps the realistic cost negligible and
     * the worst case bounded and stateable, which a larger number would not.
     */
    static final int MAX_CACHED_STORES = 8;

    private final Path storageRoot;
    private final Map<String, RepositoryLock> locks = new ConcurrentHashMap<>();

    /**
     * Object stores kept between opens, most recently used last.
     *
     * <p>The reason this exists is the cache inside each store. Every API request
     * used to build a new store and therefore a new, empty verified-object cache,
     * so a repository read a moment ago was read again from disk, inflated again
     * and hashed again. Keeping the store keeps what it has already verified.
     *
     * <p>Only the store is kept. Everything else a repository handle holds is
     * still built per open, which matters most for {@link
     * com.gitforge.vcs.graph.CommitGraph}: its parent memo is unbounded by
     * design, correct for one traversal and not something to grow for the life of
     * a process. Measured, sharing the store alone carries almost all of the
     * benefit - a first page of history goes from 55.5 ms to 7.1 ms - and sharing
     * the memo as well would add about a factor of two in exchange for an
     * unbounded map per repository. That trade is not worth making.
     *
     * <p>Bounded, and evicted least-recently-used. Eviction drops a reference and
     * nothing else: a request already holding an evicted store keeps working with
     * it, and the store becomes collectable when that request lets go. There is
     * nothing to close, which is what makes eviction safe at any moment.
     */
    private final Map<String, ObjectStore> stores =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ObjectStore> eldest) {
                    return size() > MAX_CACHED_STORES;
                }
            };

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
        // A repository being created here is new storage, whatever used to be at
        // this identifier. Storage removed by something other than delete - a
        // test clearing its directory, an operator - would otherwise leave a
        // store behind that describes what is no longer there.
        forgetStore(id);
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

    /**
     * Removes one repository's storage entirely.
     *
     * <p>The counterpart to {@link #initialise}: what that created, this takes
     * away, so a deleted repository stops occupying disk instead of leaving its
     * objects behind with nothing pointing at them.
     *
     * <p>The path comes from {@link #pathFor}, so the same guard that stops an
     * id reaching outside the storage root applies here — where it matters most,
     * because this call deletes what it is given.
     *
     * <p>Absence is success. Deleting a repository whose storage was never
     * created, or has already been removed, is not an error: the caller wanted
     * it gone, and it is.
     *
     * @return true if a directory was removed, false if there was nothing there
     * @throws IOException if the directory exists but could not be removed
     */
    public boolean delete(RepositoryId id) throws IOException {
        Path root = pathFor(id);

        // Before the files go, so that nothing can put an entry back afterwards
        // by reading through a store that is about to describe a directory which
        // no longer exists.
        forgetStore(id);

        if (!Files.isDirectory(root)) {
            return false;
        }

        try (var paths = Files.walk(root)) {
            // Deepest first, so every directory is empty by the time it is
            // reached. Collected before deleting: the walk is lazy, and removing
            // entries from underneath it is not something to rely on.
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
        return true;
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
        // The store is shared between opens; everything else is not.
        RefStore refs = new FileSystemRefStore(root);
        return new VcsRepository(id, storeFor(id, root), refs, lockFor(id));
    }

    /**
     * The object store for one repository, kept from the last open if it is
     * still cached.
     *
     * <p>Sharing this across requests is safe because the store holds no state
     * that describes the repository: what it caches is object <em>content</em>,
     * keyed by the hash of that content, and every read still asks the
     * filesystem whether the file is there and whether it is the same file the
     * entry was verified against. A cached entry can therefore never answer for
     * an object that has been swept, replaced or damaged since - which is what
     * makes the lifetime of the cache a performance question rather than a
     * correctness one.
     */
    private ObjectStore storeFor(RepositoryId id, Path root) {
        synchronized (stores) {
            return stores.computeIfAbsent(id.value(), key -> new FileSystemObjectStore(root));
        }
    }

    /**
     * Forgets a repository's cached store.
     *
     * <p>Called wherever storage stops being what the cached store was built
     * over, so that a repository created again at the same identifier starts
     * from an empty cache rather than inheriting the previous one.
     */
    private void forgetStore(RepositoryId id) {
        synchronized (stores) {
            stores.remove(id.value());
        }
    }

    /** How many stores are currently kept. Test seam for the bound. */
    int cachedStoreCount() {
        synchronized (stores) {
            return stores.size();
        }
    }

    /**
     * The lock shared by every open handle to one repository.
     *
     * <p>Opening builds a fresh handle every time — only the object store is
     * carried over — so two concurrent requests hold two {@link VcsRepository}
     * instances backed by the same bytes. A lock held inside either of them would
     * exclude nobody. Keying it here, by id, is what makes exclusion mean
     * something.
     *
     * <p>Entries are never evicted. One lock per repository the process has
     * touched is a few dozen bytes against storage measured in objects, and
     * evicting one while a sweep held it is a bug that would be very hard to see.
     */
    /**
     * The lock for one repository.
     *
     * <p>Cached per identifier so every view this factory hands out shares one,
     * and built with the repository's own directory so it also excludes other
     * processes. Two factories over the same storage still hold two objects —
     * that is unavoidable, they are separate objects — but they now contend for
     * the same file lock underneath, which is what makes them safe together.
     */
    /**
     * Where one repository takes its cross-process lock.
     *
     * <p>Beside the repositories rather than inside one. The directory name is
     * not a legal repository id - ids allow only letters, digits, dot, underscore
     * and hyphen - so it can never collide with a repository somebody creates.
     */
    Path lockFileFor(RepositoryId id) {
        return storageRoot.resolve("~locks").resolve(id.value() + ".lock");
    }

    private RepositoryLock lockFor(RepositoryId id) {
        return locks.computeIfAbsent(id.value(), key -> new RepositoryLock(lockFileFor(id)));
    }
}
