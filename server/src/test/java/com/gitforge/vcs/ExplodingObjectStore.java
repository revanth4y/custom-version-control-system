package com.gitforge.vcs;

import com.gitforge.vcs.object.Blob;
import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.ObjectType;
import com.gitforge.vcs.object.Tree;
import com.gitforge.vcs.object.VcsObject;
import com.gitforge.vcs.storage.ObjectStore;
import com.gitforge.vcs.storage.ObjectStoreException;

import java.util.List;
import java.util.Optional;

/**
 * A store that fails on demand, so failure safety can be demonstrated rather
 * than argued.
 *
 * <p>The claim being tested — "if persistence fails partway through, the branch
 * still points where it did" — is about what does <em>not</em> happen, and no
 * amount of successful-path testing establishes it. Injecting a failure at a
 * chosen point turns it into something observable.
 *
 * <p>Two triggers are useful in practice: failing on the <em>n</em>-th write, and
 * failing on the first write of a particular object type, which pinpoints the
 * gap between "the tree is stored" and "the commit is stored".
 */
public final class ExplodingObjectStore implements ObjectStore {

    private final ObjectStore delegate;

    private int writesUntilFailure = Integer.MAX_VALUE;
    private ObjectType failOnType;
    private int writeCount;

    public ExplodingObjectStore(ObjectStore delegate) {
        this.delegate = delegate;
    }

    /** Lets {@code count} writes succeed, then fails every write after them. */
    public ExplodingObjectStore failAfterWrites(int count) {
        this.writesUntilFailure = count;
        return this;
    }

    /** Fails on any attempt to write an object of this type. */
    public ExplodingObjectStore failOnWritingType(ObjectType type) {
        this.failOnType = type;
        return this;
    }

    /** Stops failing, so a test can verify the repository still works afterwards. */
    public void defuse() {
        this.writesUntilFailure = Integer.MAX_VALUE;
        this.failOnType = null;
    }

    public int writeCount() {
        return writeCount;
    }

    @Override
    public ObjectId write(VcsObject object) {
        if (failOnType != null && object.type() == failOnType) {
            throw new ObjectStoreException("Injected failure writing a " + object.type().header());
        }
        if (writeCount >= writesUntilFailure) {
            throw new ObjectStoreException("Injected failure after " + writeCount + " writes");
        }
        writeCount++;
        return delegate.write(object);
    }

    @Override
    public Optional<VcsObject> read(ObjectId id) {
        return delegate.read(id);
    }

    @Override
    public Blob readBlob(ObjectId id) {
        return delegate.readBlob(id);
    }

    @Override
    public Tree readTree(ObjectId id) {
        return delegate.readTree(id);
    }

    @Override
    public Commit readCommit(ObjectId id) {
        return delegate.readCommit(id);
    }

    @Override
    public boolean contains(ObjectId id) {
        return delegate.contains(id);
    }

    @Override
    public void verify(ObjectId id) {
        delegate.verify(id);
    }

    @Override
    public long count() {
        return delegate.count();
    }

    @Override
    public List<ObjectId> listIds() {
        return delegate.listIds();
    }
}
