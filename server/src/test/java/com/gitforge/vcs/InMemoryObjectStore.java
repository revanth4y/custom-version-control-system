package com.gitforge.vcs;

import com.gitforge.vcs.object.Blob;
import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.CorruptObjectException;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Tree;
import com.gitforge.vcs.object.VcsObject;
import com.gitforge.vcs.storage.ObjectStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An object store held in a map, for tests that do not need the filesystem.
 *
 * <p>It also serves a purpose the real store deliberately cannot: {@link #forge}
 * files an object under an arbitrary id, which the filesystem store would reject
 * because it re-hashes everything it reads. That is the only way to construct a
 * cyclic history — a genuine cycle is unreachable through the normal API, since
 * a commit's id is a hash of bytes containing its parents' ids, so forming one
 * would require a SHA-1 preimage.
 *
 * <p>Being able to build a malformed graph is what lets the traversal tests prove
 * they terminate on one, rather than merely asserting that they should.
 */
public final class InMemoryObjectStore implements ObjectStore {

    private final Map<ObjectId, VcsObject> objects = new LinkedHashMap<>();

    @Override
    public ObjectId write(VcsObject object) {
        ObjectId id = object.id();
        objects.putIfAbsent(id, object);
        return id;
    }

    /**
     * Stores {@code object} under an id that is not its own.
     *
     * <p>For tests only. This is exactly the corruption the real store detects.
     */
    public void forge(ObjectId id, VcsObject object) {
        objects.put(id, object);
    }

    @Override
    public Optional<VcsObject> read(ObjectId id) {
        return Optional.ofNullable(objects.get(id));
    }

    @Override
    public Blob readBlob(ObjectId id) {
        return require(id, Blob.class, "blob");
    }

    @Override
    public Tree readTree(ObjectId id) {
        return require(id, Tree.class, "tree");
    }

    @Override
    public Commit readCommit(ObjectId id) {
        return require(id, Commit.class, "commit");
    }

    @Override
    public boolean contains(ObjectId id) {
        return objects.containsKey(id);
    }

    @Override
    public void verify(ObjectId id) {
        if (!contains(id)) {
            throw new CorruptObjectException("Object " + id + " is missing from the store");
        }
    }

    @Override
    public long count() {
        return objects.size();
    }

    @Override
    public List<ObjectId> listIds() {
        return new ArrayList<>(objects.keySet());
    }

    private <T extends VcsObject> T require(ObjectId id, Class<T> expected, String description) {
        VcsObject object = objects.get(id);
        if (object == null) {
            throw new CorruptObjectException("Object " + id + " is missing from the store");
        }
        if (!expected.isInstance(object)) {
            throw new CorruptObjectException(
                    "Object " + id + " is a " + object.type().header() + ", not a " + description);
        }
        return expected.cast(object);
    }
}
