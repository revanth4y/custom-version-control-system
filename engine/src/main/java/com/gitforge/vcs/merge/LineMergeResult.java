package com.gitforge.vcs.merge;

import java.util.List;

/**
 * The outcome of merging one file's lines.
 *
 * <p>Mirrors {@link MergeResult} deliberately: a conflicted line merge produces
 * <em>no</em> content, for the same reason a conflicted tree merge produces no
 * tree. Text with markers written into it would be a result a caller could
 * store by accident, and this engine has no working copy for anyone to resolve
 * them in.
 */
public sealed interface LineMergeResult permits LineMergeResult.Clean, LineMergeResult.Conflicted {

    boolean isClean();

    /** Every region resolved; {@code content} is the merged file. */
    record Clean(String content) implements LineMergeResult {

        public Clean {
            if (content == null) {
                throw new IllegalArgumentException("A clean line merge must produce content");
            }
        }

        @Override
        public boolean isClean() {
            return true;
        }
    }

    /**
     * At least one region could not be resolved.
     *
     * @param regions the unresolved stretches, in the order they appear in the file
     */
    record Conflicted(List<ConflictRegion> regions) implements LineMergeResult {

        public Conflicted {
            if (regions == null || regions.isEmpty()) {
                throw new IllegalArgumentException("A conflicted line merge must report at least one region");
            }
            regions = List.copyOf(regions);
        }

        @Override
        public boolean isClean() {
            return false;
        }
    }
}
