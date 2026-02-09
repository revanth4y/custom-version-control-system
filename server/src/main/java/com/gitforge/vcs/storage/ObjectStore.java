package com.gitforge.vcs.storage;

import com.gitforge.vcs.object.Blob;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Tree;
import com.gitforge.vcs.object.VcsObject;

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

    boolean contains(ObjectId id);

    /**
     * Verifies the object stored under {@code id}.
     *
     * @throws com.gitforge.vcs.object.CorruptObjectException if it is damaged
     */
    void verify(ObjectId id);

    /** The number of distinct objects held. */
    long count();
}
