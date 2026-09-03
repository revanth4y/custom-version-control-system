package com.gitforge.vcs.merge;

import com.gitforge.vcs.object.FileMode;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.TreeEntry;

import java.util.List;
import java.util.Optional;

/**
 * A path the merge could not resolve, with what each side had there.
 *
 * <p>A single uniform record rather than a type per {@link ConflictKind}: every
 * conflict carries the same three optional sides, and only which of them are
 * present varies. Separate records would repeat the same fields five times
 * without buying any type safety.
 *
 * <p>An absent side means the path did not exist there, so the direction of a
 * {@link ConflictKind#MODIFY_DELETE} is read directly off the data: if
 * {@code ours} is present and {@code theirs} is empty, we changed it and they
 * deleted it.
 *
 * @param path the repository-relative path where the sides diverge
 * @param regions the stretches of the file that disagree, when a line-level
 *     merge got far enough to say. Empty whenever it did not — a binary file, a
 *     path that is a directory on one side, or a file too large to align — which
 *     is a statement about how much is known, not about how much conflicts
 */
public record MergeConflict(
        ConflictKind kind,
        String path,
        Optional<Side> base,
        Optional<Side> ours,
        Optional<Side> theirs,
        List<ConflictRegion> regions) {

    /** What one side had at the conflicting path. */
    public record Side(FileMode mode, ObjectId id) {

        public boolean isDirectory() {
            return mode.isDirectory();
        }

        static Optional<Side> of(TreeEntry entry) {
            return entry == null ? Optional.empty() : Optional.of(new Side(entry.mode(), entry.id()));
        }
    }

    public MergeConflict {
        if (kind == null || path == null) {
            throw new IllegalArgumentException("A conflict needs a kind and a path");
        }
        regions = regions == null ? List.of() : List.copyOf(regions);
    }

    /** A conflict nothing is known about beyond the path itself. */
    public MergeConflict(
            ConflictKind kind,
            String path,
            Optional<Side> base,
            Optional<Side> ours,
            Optional<Side> theirs) {

        this(kind, path, base, ours, theirs, List.of());
    }

    static MergeConflict of(
            ConflictKind kind,
            String path,
            TreeEntry base,
            TreeEntry ours,
            TreeEntry theirs,
            List<ConflictRegion> regions) {

        return new MergeConflict(kind, path, Side.of(base), Side.of(ours), Side.of(theirs), regions);
    }
}
