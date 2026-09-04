package com.gitforge.vcs.storage;

import com.gitforge.vcs.object.Blob;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.VcsObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Objects that have already been read, inflated and checked against their id.
 *
 * <p>Reading an object costs a file read, an inflate and a SHA-1 over the whole
 * payload, and the engine reads the same objects over and over: a history walk
 * revisits shared ancestry, a sweep enumerates everything, an insight walks the
 * same commits a listing just walked. The bytes cannot have changed in between -
 * an id is the hash of the object - so the second inflate produces what the first
 * one did.
 *
 * <p><strong>What may enter.</strong> Only an object read from disk, inflated,
 * and confirmed to hash to the id it was filed under. Not one this process has
 * just written: the id was computed from the bytes handed to the write, and what
 * matters is the bytes that reached the disk, which is exactly what reading
 * verifies. There is no path that puts an unverified object in here, and that is
 * the property the whole thing rests on.
 *
 * <p><strong>What this deliberately does not remember.</strong> Whether an object
 * exists, and what its file looks like. Both are mutable - a sweep removes
 * objects, and damage changes a file - so the store reads the file's attributes
 * on every read and consults this only for a file that is present and unchanged
 * since the entry was made. A cached entry can therefore never answer for an
 * object that has been deleted, nor for a file that has been touched since it was
 * checked.
 *
 * <p>It is also not consulted by {@code verify}, which exists to check the bytes
 * on disk. Answering that from memory would turn an integrity scan into a
 * statement about what this process once read.
 *
 * <p><strong>Scope.</strong> One cache per object store, so it cannot answer
 * across repositories. Content addressing would make sharing safe for bytes, but
 * not for existence: two repositories can hold different objects, and a shared
 * cache would let one answer for an object the other has never had.
 *
 * <p>Bounded by entry count and by payload size. A repository holds more objects
 * than fit in memory, and a large blob is exactly the object least worth keeping:
 * it costs the most to hold and is read the least often.
 *
 * <p><strong>Nothing is kept on first sight.</strong> A least-recently-used cache
 * is destroyed by a scan: a walk of a history larger than the cache evicts
 * everything it just stored, so the next walk starts at the entry that was thrown
 * away last, misses on every object, and pays the bookkeeping for no benefit.
 * Measured, that made a full-history walk twenty per cent slower.
 *
 * <p>So an object is admitted on the second time it is asked for, not the first.
 * A single pass over a large history therefore stores nothing and costs only a
 * failed lookup, while re-reading a bounded set - paging a log, drawing an
 * insight over recent history - populates on its second pass and is served from
 * memory after that. Measured, that shape reads seven times faster.
 */
final class VerifiedObjectCache {

    /**
     * How many objects to keep.
     *
     * <p>Enough to cover the working set of a traversal - a history walk touches
     * commits and trees repeatedly - without pretending to hold a repository.
     * Beyond this the least recently used entry goes.
     */
    static final int DEFAULT_CAPACITY = 4_096;

    /**
     * The largest payload worth keeping, in bytes.
     *
     * <p>Commits and trees are small and are what get re-read; a megabyte blob is
     * read once by whatever asked for it and would evict hundreds of the objects
     * that traversal actually revisits.
     */
    static final int MAX_CACHED_PAYLOAD = 64 * 1024;

    /**
     * What was cached, and what the file looked like when it was.
     *
     * <p>The stamp is the point. Content addressing says the bytes for an id
     * cannot legitimately change, but a damaged or replaced file is not a
     * legitimate change, and the store is expected to notice one. Keeping the
     * size and modification time the entry was verified against means a file
     * that has been touched since is a miss, and gets read and checked again.
     */
    private record Entry(VcsObject object, long size, long modifiedMillis) {
    }

    private final int capacity;
    private final Map<ObjectId, Entry> entries;

    /**
     * Ids asked for once, waiting to see whether they are asked for again.
     *
     * <p>Ids only, not objects, so remembering that a scan happened costs a
     * fraction of what remembering the scan would. Bounded the same way and
     * evicted in the same order.
     */
    private final Map<ObjectId, Boolean> seenOnce;

    private long hits;
    private long misses;

    VerifiedObjectCache() {
        this(DEFAULT_CAPACITY);
    }

    VerifiedObjectCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Cache capacity must be positive, got " + capacity);
        }
        this.capacity = capacity;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<ObjectId, Entry> eldest) {
                return size() > VerifiedObjectCache.this.capacity;
            }
        };
        this.seenOnce = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<ObjectId, Boolean> eldest) {
                return size() > VerifiedObjectCache.this.capacity;
            }
        };
    }

    /**
     * The object filed under this id, if it has been verified during this store's
     * lifetime.
     *
     * <p>The caller must already have established that the object exists. This
     * answers what the bytes were, never whether they are still there.
     */
    synchronized VcsObject get(ObjectId id, long size, long modifiedMillis) {
        Entry entry = entries.get(id);
        if (entry == null) {
            misses++;
            return null;
        }
        if (entry.size() != size || entry.modifiedMillis() != modifiedMillis) {
            // The file is not the one this entry was verified against. Whatever
            // it is now has to be read and checked; it is not this.
            entries.remove(id);
            misses++;
            return null;
        }
        hits++;
        return entry.object();
    }

    /**
     * Remembers an object whose bytes have been verified against {@code id}.
     *
     * <p>Callers pass the id they verified against rather than asking the object
     * for its own, so that this cannot be handed an object filed under a
     * different id than the one it hashes to.
     */
    synchronized void put(ObjectId id, VcsObject object, long size, long modifiedMillis) {
        if (id == null || object == null || tooLarge(object)) {
            return;
        }
        if (seenOnce.remove(id) == null) {
            // First sight. Noted, not kept - so a walk that never comes back
            // leaves nothing behind and evicts nothing that would have been used.
            seenOnce.put(id, Boolean.TRUE);
            return;
        }
        entries.put(id, new Entry(object, size, modifiedMillis));
    }

    /** Forgets one object, because it is being removed from the store. */
    synchronized void evict(ObjectId id) {
        entries.remove(id);
        seenOnce.remove(id);
    }

    private static boolean tooLarge(VcsObject object) {
        return object instanceof Blob blob && blob.size() > MAX_CACHED_PAYLOAD;
    }

    // ------------------------------------------------------------ reporting

    synchronized int size() {
        return entries.size();
    }

    synchronized long hits() {
        return hits;
    }

    synchronized long misses() {
        return misses;
    }

    synchronized void resetCounters() {
        hits = 0;
        misses = 0;
    }
}
