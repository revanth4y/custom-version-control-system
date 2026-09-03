package com.gitforge.vcs.repository;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Coordination between the operations that write objects and the one operation
 * that removes them.
 *
 * <p>The naming looks inverted until you see why. Ordinary writes take the
 * <em>shared</em> side: the object store is content-addressed and append-only, so
 * two concurrent commits cannot interfere — writing the same content twice is a
 * no-op and writing different content touches different files. They never needed
 * to exclude one another, and this class does not start making them.
 *
 * <p>Garbage collection takes the <em>exclusive</em> side, because it is the first
 * operation in the system that removes bytes. Deletion is what breaks the
 * append-only invariant, and it is deletion alone that has to be kept apart from
 * everything else.
 *
 * <p>What that exclusion buys is the whole of the safety argument. A commit writes
 * its blobs, then its trees, then the commit object, and only then moves the
 * branch — for the length of that sequence every object it has written is
 * unreachable from any reference. A sweep that ran in the middle would see
 * legitimately-in-progress work as garbage, delete it, and leave the branch
 * update that followed pointing at a commit whose tree no longer exists. Holding
 * the exclusive lock across both the reachability calculation and the deletion
 * means no such sequence can be part-way through in either phase.
 *
 * <p>The scope is one repository. Repositories share no storage — each has its own
 * directory and its own object store — so a sweep of one has nothing to say about
 * another, and a lock any wider would create contention that buys no safety.
 *
 * <p>This is an in-process lock, which is sufficient for the way GitForge is
 * deployed: a single server process owns the storage root. It would not be
 * sufficient for several processes sharing a volume, and that limitation is
 * recorded rather than designed around, because nothing in the repository
 * currently runs that way.
 */
public final class RepositoryLock {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Runs an operation that may write objects or move references.
     *
     * <p>Shared with other writers, excluded from collection. Reentrant, so an
     * operation built out of smaller ones — a branch created from a revision, a
     * commit that delegates to a longer overload — does not deadlock against
     * itself.
     */
    public <T> T shared(Supplier<T> operation) {
        lock.readLock().lock();
        try {
            return operation.get();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** As {@link #shared(Supplier)}, for an operation with no result. */
    public void shared(Runnable operation) {
        shared(() -> {
            operation.run();
            return null;
        });
    }

    /**
     * Runs an operation that removes objects, excluding every writer for its
     * duration.
     *
     * <p>Both phases of a sweep belong inside one call. Computing reachability
     * under exclusion and then releasing before deleting would reintroduce
     * exactly the race the lock exists to close.
     */
    public <T> T exclusive(Supplier<T> operation) {
        lock.writeLock().lock();
        try {
            return operation.get();
        } finally {
            lock.writeLock().unlock();
        }
    }
}
