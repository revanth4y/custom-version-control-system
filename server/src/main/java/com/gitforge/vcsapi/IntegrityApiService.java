package com.gitforge.vcsapi;

import com.gitforge.user.User;
import com.gitforge.vcs.object.CorruptObjectException;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.storage.ObjectStore;
import com.gitforge.vcsapi.dto.IntegrityReport;
import com.gitforge.vcsapi.dto.IntegrityReport.DamagedObject;
import com.gitforge.vcsapi.dto.IntegrityReport.Reason;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Proving the object store's central invariant, one object at a time.
 *
 * <p>A content-addressed store claims that an object's location <em>is</em> the
 * SHA-1 of its canonical representation. Nothing had ever tested that claim
 * against the bytes actually on disk. This does: it enumerates the store and
 * asks the engine to read each object back, which decompresses it, parses it,
 * re-hashes the framed form and rejects anything that does not match the id it
 * was filed under.
 *
 * <p><strong>The hashing is the engine's, not this class's.</strong>
 * {@link ObjectStore#verify(ObjectId)} performs the real comparison; duplicating
 * it here would mean a second implementation that could drift from the one that
 * actually stores objects, and a scan agreeing with itself proves nothing.
 *
 * <p>Corruption is the result this endpoint exists to find, so it is collected
 * and returned rather than thrown. Letting {@link CorruptObjectException} escape
 * would reach the global handler and become a 500 — the integrity check would
 * fail precisely when it succeeded.
 */
@Service
public class IntegrityApiService {

    /**
     * The most objects one request will verify.
     *
     * <p>Verification is the only read path whose cost scales with stored bytes
     * rather than with the size of the answer, and the endpoint is reachable
     * anonymously for public repositories. A fixed ceiling bounds that without a
     * client-supplied limit someone could raise, and without the machinery of a
     * background job for repositories that will never approach it.
     */
    static final int DEFAULT_MAX_VERIFIED_OBJECTS = 10_000;

    /**
     * The one phrase this class reads from an engine message.
     *
     * <p>Both remaining failures arrive as {@link CorruptObjectException} and are
     * told apart only by what the store says: bytes that rebuilt into an object
     * but hashed to something else, versus bytes that never rebuilt at all. The
     * coupling is narrow and every reason is pinned by a test, so a reworded
     * message fails the build rather than quietly misclassifying damage.
     */
    private static final String HASH_MISMATCH_MARKER = "actually hashes to";

    private final VcsRepositoryProvider repositories;
    private final int maxVerifiedObjects;

    /**
     * Marked explicitly because there is a second constructor for tests, and
     * without this Spring has two candidates and picks neither.
     */
    @Autowired
    public IntegrityApiService(VcsRepositoryProvider repositories) {
        this(repositories, DEFAULT_MAX_VERIFIED_OBJECTS);
    }

    /** Visible for tests, so truncation can be exercised without ten thousand objects. */
    IntegrityApiService(VcsRepositoryProvider repositories, int maxVerifiedObjects) {
        if (maxVerifiedObjects < 1) {
            throw new IllegalArgumentException("The verification cap must be at least one object");
        }
        this.repositories = repositories;
        this.maxVerifiedObjects = maxVerifiedObjects;
    }

    /**
     * Verifies every stored object, up to the cap.
     *
     * <p>Authorization is {@link VcsRepositoryProvider#forRead}, the same
     * visibility rule as every other repository read, so a private repository
     * stays invisible to anyone but its owner.
     */
    public IntegrityReport verify(String owner, String name, User viewer) {
        return scan(repositories.forRead(owner, name, viewer).objects());
    }

    /**
     * The scan itself, with authorization already applied.
     *
     * <p>Separated from {@link #verify} so it can be exercised against a real
     * object store rather than a mocked repository — the point of this feature is
     * that hashing actually happens, which a stubbed store would not prove.
     */
    IntegrityReport scan(ObjectStore objects) {
        // Sorted so truncation is deterministic: the same repository scanned
        // twice checks the same objects, rather than whichever the filesystem
        // happened to walk first.
        List<ObjectId> stored = objects.listIds().stream()
                .sorted(Comparator.comparing(ObjectId::toHex))
                .toList();

        boolean truncated = stored.size() > maxVerifiedObjects;
        List<ObjectId> selected = truncated ? stored.subList(0, maxVerifiedObjects) : stored;

        long startedAt = System.nanoTime();
        List<DamagedObject> damaged = new ArrayList<>();
        for (ObjectId id : selected) {
            inspect(objects, id).ifPresent(damaged::add);
        }
        long durationMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        return new IntegrityReport(
                stored.size(),
                selected.size(),
                List.copyOf(damaged),
                // Nothing verified is not the same as nothing wrong.
                selected.isEmpty() ? null : damaged.isEmpty(),
                truncated,
                Instant.now(),
                durationMs);
    }

    /** Empty when the object verified; otherwise how it failed. */
    private Optional<DamagedObject> inspect(ObjectStore objects, ObjectId id) {
        // Asked structurally rather than read from a message: the id came from a
        // file that existed a moment ago, so its absence now means it was removed
        // mid-scan. Rare, but reporting it as unreadable would be wrong.
        if (!objects.contains(id)) {
            return Optional.of(damaged(id, Reason.MISSING));
        }

        try {
            objects.verify(id);
            return Optional.empty();
        } catch (CorruptObjectException ex) {
            String message = ex.getMessage() == null ? "" : ex.getMessage();
            return Optional.of(damaged(
                    id, message.contains(HASH_MISMATCH_MARKER) ? Reason.HASH_MISMATCH : Reason.UNREADABLE));
        }
    }

    /**
     * The detail is the reason's own fixed phrase, never the exception text.
     *
     * <p>Engine messages are written for a log and can carry bytes read straight
     * out of a damaged header. A closed vocabulary cannot leak a path, a length,
     * or anything else about how objects are stored.
     */
    private static DamagedObject damaged(ObjectId id, Reason reason) {
        return new DamagedObject(id.toHex(), reason, reason.detail());
    }
}
