package com.gitforge.vcs.insights;

import com.gitforge.vcs.object.CorruptObjectException;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.ObjectType;
import com.gitforge.vcs.object.VcsObject;
import com.gitforge.vcs.storage.ObjectStore;
import com.gitforge.vcs.storage.ObjectStoreException;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a repository is made of, by object type and by bytes.
 *
 * <p>This is the view GitHub cannot offer, because it never exposes its object
 * store. Every figure comes from enumerating the store and reading each object;
 * none of it is estimated from anything else.
 *
 * <p><strong>Types are read from the enum, never hard-coded.</strong> The object
 * model gained a fourth type in V2.0.14 and will gain more; a distribution that
 * listed today's three would quietly stop summing to the total the day a fifth
 * arrived. Every {@link ObjectType} appears in the result, including the ones
 * with a count of zero, so a caller can see the whole vocabulary rather than
 * inferring which types were omitted.
 *
 * <p><strong>Truncation is reported, never hidden.</strong> The scan stops at the
 * same ceiling verification and collection use. A partial scan presented as a
 * repository total would be a fabricated statistic, so when the store holds more
 * than the ceiling admits the result says so and says how far it got.
 */
public final class StorageInsights {

    /**
     * The most objects one scan will read.
     *
     * <p>The same figure {@code IntegrityApiService} and {@code GarbageCollector}
     * use, and for the same reason: the cost scales with what is stored rather
     * than with the size of the answer.
     */
    public static final int DEFAULT_MAX_SCANNED_OBJECTS = 10_000;

    private final ObjectStore objects;
    private final int maxScanned;

    public StorageInsights(ObjectStore objects) {
        this(objects, DEFAULT_MAX_SCANNED_OBJECTS);
    }

    /** Visible for tests, so truncation can be exercised without ten thousand objects. */
    StorageInsights(ObjectStore objects, int maxScanned) {
        if (objects == null) {
            throw new IllegalArgumentException("Storage insights need an object store");
        }
        if (maxScanned < 1) {
            throw new IllegalArgumentException("The scan ceiling must be at least one object");
        }
        this.objects = objects;
        this.maxScanned = maxScanned;
    }

    /** One object type, and what the store holds of it. */
    public record TypeUsage(ObjectType type, int count, long bytes) {
    }

    /**
     * @param storedObjects what the store reports holding, whether or not the scan
     *     reached all of it
     * @param scannedObjects how many were actually read
     * @param truncated true when the store holds more than the ceiling admits, in
     *     which case the per-type figures describe the scan and not the repository
     * @param unreadable objects enumerated but not readable; counted rather than
     *     silently dropped, so the arithmetic still adds up
     */
    public record Usage(
            long storedObjects,
            int scannedObjects,
            long scannedBytes,
            boolean truncated,
            int unreadable,
            List<TypeUsage> byType) {

        /** Whether the per-type figures describe the whole repository. */
        public boolean complete() {
            return !truncated;
        }
    }

    public Usage compute() {
        long stored = objects.count();
        List<ObjectId> ids = objects.listIds();
        boolean truncated = ids.size() > maxScanned;

        Map<ObjectType, Integer> counts = new EnumMap<>(ObjectType.class);
        Map<ObjectType, Long> bytes = new EnumMap<>(ObjectType.class);
        for (ObjectType type : ObjectType.values()) {
            counts.put(type, 0);
            bytes.put(type, 0L);
        }

        int scanned = 0;
        int unreadable = 0;
        long scannedBytes = 0;

        for (ObjectId id : ids) {
            if (scanned + unreadable >= maxScanned) {
                break;
            }
            Optional<VcsObject> object = readQuietly(id);
            if (object.isEmpty()) {
                unreadable++;
                continue;
            }
            ObjectType type = object.get().type();
            long size = sizeQuietly(id);

            counts.merge(type, 1, Integer::sum);
            bytes.merge(type, size, Long::sum);
            scannedBytes += size;
            scanned++;
        }

        List<TypeUsage> byType = java.util.Arrays.stream(ObjectType.values())
                .map(type -> new TypeUsage(type, counts.get(type), bytes.get(type)))
                .toList();

        return new Usage(stored, scanned, scannedBytes, truncated, unreadable, byType);
    }

    private Optional<VcsObject> readQuietly(ObjectId id) {
        try {
            return objects.read(id);
        } catch (CorruptObjectException | ObjectStoreException ex) {
            return Optional.empty();
        }
    }

    /**
     * An object's size on disk, or zero when the store cannot measure it.
     *
     * <p>Zero rather than a guess. A store that does not implement measurement
     * refuses rather than approximating, and inventing a number here would put an
     * estimate into a figure this class exists to keep honest.
     */
    private long sizeQuietly(ObjectId id) {
        try {
            return objects.sizeOf(id);
        } catch (RuntimeException ex) {
            return 0L;
        }
    }
}
