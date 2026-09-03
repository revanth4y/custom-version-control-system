package com.gitforge.vcs;

import com.gitforge.vcs.object.Blob;
import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Tree;
import com.gitforge.vcs.object.VcsObject;
import com.gitforge.vcs.storage.ObjectStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Wraps a store and counts what passes through it.
 *
 * <p>Short-circuiting is the central claim of the diff and merge algorithms: an
 * untouched subtree must cost nothing. That is a claim about work <em>not</em>
 * done, which no ordinary assertion can capture — a correct result proves
 * nothing about how expensively it was reached. Counting reads turns it into
 * something measurable, so a regression that quietly starts walking the whole
 * repository fails a test instead of merely getting slower.
 */
public final class CountingObjectStore implements ObjectStore {

    private final ObjectStore delegate;
    private final List<ObjectId> reads = new ArrayList<>();
    private final List<ObjectId> writes = new ArrayList<>();
    private final List<String> prefixSearches = new ArrayList<>();

    public CountingObjectStore(ObjectStore delegate) {
        this.delegate = delegate;
    }

    /** Forgets everything counted so far, so a test can measure one operation. */
    public void resetCounts() {
        reads.clear();
        writes.clear();
        prefixSearches.clear();
    }

    public int readCount() {
        return reads.size();
    }

    public int writeCount() {
        return writes.size();
    }

    /**
     * How many prefix searches were run since the last reset.
     *
     * <p>Also a claim about work not done: resolving a full id must not go
     * looking through a directory for something it already has in hand.
     */
    public int prefixSearchCount() {
        return prefixSearches.size();
    }

    /** Every id read since the last reset, in order. */
    public List<ObjectId> readIds() {
        return List.copyOf(reads);
    }

    public List<ObjectId> writtenIds() {
        return List.copyOf(writes);
    }

    /** Whether a particular object was read — used to prove a subtree was skipped. */
    public boolean hasRead(ObjectId id) {
        return reads.contains(id);
    }

    @Override
    public ObjectId write(VcsObject object) {
        writes.add(object.id());
        return delegate.write(object);
    }

    @Override
    public Optional<VcsObject> read(ObjectId id) {
        reads.add(id);
        return delegate.read(id);
    }

    @Override
    public Blob readBlob(ObjectId id) {
        reads.add(id);
        return delegate.readBlob(id);
    }

    @Override
    public Tree readTree(ObjectId id) {
        reads.add(id);
        return delegate.readTree(id);
    }

    @Override
    public Commit readCommit(ObjectId id) {
        reads.add(id);
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
    public List<ObjectId> findByPrefix(String hexPrefix) {
        prefixSearches.add(hexPrefix);
        return delegate.findByPrefix(hexPrefix);
    }

    @Override
    public List<ObjectId> listIds() {
        return delegate.listIds();
    }
}
