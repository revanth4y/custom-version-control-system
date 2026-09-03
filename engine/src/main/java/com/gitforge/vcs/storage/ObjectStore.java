package com.gitforge.vcs.storage;

import com.gitforge.vcs.object.Blob;
import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Tree;
import com.gitforge.vcs.object.VcsObject;

import java.util.List;
import java.util.Optional;

/**
 * Content-addressed storage for objects.
 *
 * <p>Objects are keyed by the hash of their own contents, which gives the store
 * two properties the rest of the system depends on: writing the same content
 * twice is a no-op, and any object read back can be checked against the id it
 * was requested under.
 *
 * <p>Deliberately free of Spring, HTTP and persistence concerns so the engine can
 * be exercised without an application context.
 */
public interface ObjectStore {

    /**
     * Stores an object and returns its id.
     *
     * <p>If content with this id is already present, nothing is written: the
     * stored bytes are by definition identical.
     */
    ObjectId write(VcsObject object);

    /**
     * Reads an object, verifying that it hashes to {@code id}.
     *
     * @return empty if no object is stored under {@code id}
     * @throws com.gitforge.vcs.object.CorruptObjectException if the stored bytes
     *     cannot be decompressed or parsed, or do not hash to {@code id}
     */
    Optional<VcsObject> read(ObjectId id);

    /** Reads an object that must exist and must be a blob. */
    Blob readBlob(ObjectId id);

    /** Reads an object that must exist and must be a tree. */
    Tree readTree(ObjectId id);

    /** Reads an object that must exist and must be a commit. */
    Commit readCommit(ObjectId id);

    boolean contains(ObjectId id);

    /**
     * Verifies the object stored under {@code id}.
     *
     * @throws com.gitforge.vcs.object.CorruptObjectException if it is damaged
     */
    void verify(ObjectId id);

    /** The number of distinct objects held. */
    long count();

    /**
     * Every stored id beginning with {@code hexPrefix}.
     *
     * <p>What makes this cheap is the layout rather than an index: objects are
     * filed under the first two characters of their id, so the first two
     * characters of the prefix name the only directory that can hold a match.
     * Nothing outside it is read.
     *
     * <p>Returns every match rather than the first. A caller asking for a short
     * prefix needs to know whether it identifies one object or several, and a
     * store that answered with one of them would be guessing on the caller's
     * behalf.
     *
     * @param hexPrefix at least {@link com.gitforge.vcs.object.ObjectId#MIN_PREFIX_LENGTH}
     *     hexadecimal characters, in either case
     * @return the matching ids, empty if none; never null
     * @throws IllegalArgumentException if {@code hexPrefix} is not a valid prefix
     */
    List<ObjectId> findByPrefix(String hexPrefix);

    /**
     * The ids of every stored object, derived from where each object is filed
     * rather than from its contents.
     *
     * <p>Reading these back and re-hashing them is what proves the store's
     * central invariant: an object's location is the SHA-1 of its canonical
     * representation.
     *
     * <p>Returns a materialised list rather than a stream, so callers cannot leak
     * an open directory handle. Repositories at portfolio scale hold few enough
     * objects for this to be the right trade.
     */
    List<ObjectId> listIds();

    /**
     * The size in bytes of the object as it is stored.
     *
     * <p>Storage size, not content size: an object is held compressed, and what a
     * sweep reclaims is the compressed form. Reporting the uncompressed length
     * would overstate what deleting it actually frees.
     *
     * <p>Default is to refuse rather than guess. A store that keeps objects
     * somewhere other than files has no meaningful answer, and a fabricated one
     * would be reported as reclaimed space.
     *
     * @throws UnsupportedOperationException if this store cannot measure objects
     */
    default long sizeOf(ObjectId id) {
        throw new UnsupportedOperationException(
                getClass().getSimpleName() + " cannot measure stored objects");
    }

    /**
     * Removes an object, whatever references it.
     *
     * <p>This is the only operation in the engine that destroys data, and it is
     * deliberately unguarded: the store is not the right place to decide whether
     * an object is still needed, because it cannot see references. Reachability is
     * the caller's to establish, and
     * {@link com.gitforge.vcs.gc.GarbageCollector} is the only caller that does.
     *
     * <p>Absence is success. Deleting an object twice is not an error, which is
     * what lets a sweep be safely repeatable.
     *
     * <p>Default is to refuse. A store that cannot remove objects must say so
     * rather than silently report a deletion that never happened.
     *
     * @return true if an object was removed, false if there was nothing there
     * @throws UnsupportedOperationException if this store cannot remove objects
     */
    default boolean delete(ObjectId id) {
        throw new UnsupportedOperationException(
                getClass().getSimpleName() + " cannot remove objects");
    }

    /**
     * Names of temporary files left in the store, which {@link #listIds()} hides.
     *
     * <p>Every enumeration on this interface filters them out, because a temporary
     * file is not an object and reading it as one would fail. That filtering is
     * also why nothing has ever been able to see one, and a file nothing can see
     * is a file nothing can report.
     *
     * <p>Returns names rather than ids: a temporary file has no id, which is
     * precisely what distinguishes it.
     *
     * <p>Default is none, which is the truth for a store that does not stage
     * writes through temporary files.
     */
    default List<String> temporaryFiles() {
        return List.of();
    }
}
