package com.gitforge.vcs.merge;

import com.gitforge.vcs.diff.LineDiffer;
import com.gitforge.vcs.object.TextContent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Three-way merge of one file's lines against their common base.
 *
 * <p>A pure function of three texts. It knows nothing of blobs, trees, modes or
 * the object store: deciding that two sides changed the same file, and doing
 * something with the answer, belong to {@link ThreeWayMerger}.
 *
 * <p>Without this, two branches that edit opposite ends of the same file
 * conflict, because a tree merge can only compare whole-file identities and two
 * different edits give two different ids. Comparing the lines shows that the
 * edits never meet.
 *
 * <h2>How it works</h2>
 *
 * <p>Each side is aligned against the base independently, with
 * {@link LineDiffer#align} — the same Myers computation the diff viewer uses,
 * reported as a correspondence rather than as hunks. A base line matched by
 * both sides, at the position both have currently reached, is <em>stable</em>:
 * all three agree there, and it passes through untouched.
 *
 * <p>Everything between two stable lines is a chunk, holding whatever each side
 * has there. Four cases, tested in this order:
 *
 * <ol>
 *   <li>the sides hold the same lines — they agree, including when both made
 *       the same edit;</li>
 *   <li>ours matches the base — only they changed this, so take theirs;</li>
 *   <li>theirs matches the base — only we changed this, so take ours;</li>
 *   <li>otherwise both changed it, differently: a conflict.</li>
 * </ol>
 *
 * <h2>Edits need a line between them</h2>
 *
 * <p>Two edits count as independent only when at least one untouched line
 * separates them. Changes to immediately neighbouring lines fall in the same
 * chunk and conflict, because nothing in the file says the two were meant to
 * stand together and interleaving them would be a guess about intent. This is
 * the same rule {@code git merge-file} applies, and it is the price of never
 * inventing a resolution; the behaviour is pinned by a test so that any future
 * decision to loosen it is a visible one.
 *
 * <h2>Symmetry</h2>
 *
 * <p>Swapping ours and theirs produces the same merged text and the same
 * conflicting regions with their sides exchanged. Nothing here breaks a tie by
 * preferring one side: case 1 gives the same lines whichever way round it is
 * read, cases 2 and 3 exchange into each other, and anything left is a
 * conflict rather than a silent choice. That is a property worth more than the
 * handful of extra merges a preference would buy — a merge that quietly picks a
 * winner is one whose result depends on which branch you happened to be
 * standing on.
 */
public final class LineMerger {

    private LineMerger() {
    }

    /**
     * Merges {@code ours} and {@code theirs} using {@code base} as their common
     * ancestor.
     *
     * @return the outcome, or empty when the texts exceed the bounds
     *     {@link LineDiffer} imposes — in which case the caller has learned
     *     nothing and the file is still a plain content conflict
     */
    public static Optional<LineMergeResult> merge(String base, String ours, String theirs) {
        if (base == null || ours == null || theirs == null) {
            throw new IllegalArgumentException("Merging requires a base, ours and theirs");
        }

        String[] baseLines = TextContent.lines(base);
        String[] ourLines = TextContent.lines(ours);
        String[] theirLines = TextContent.lines(theirs);

        Optional<int[]> toOurs = LineDiffer.align(baseLines, ourLines);
        Optional<int[]> toTheirs = LineDiffer.align(baseLines, theirLines);
        if (toOurs.isEmpty() || toTheirs.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new Walk(baseLines, ourLines, theirLines, toOurs.get(), toTheirs.get()).run(
                endsWithNewline(base), endsWithNewline(ours), endsWithNewline(theirs)));
    }

    /** One merge, walking the three files together. */
    private static final class Walk {

        private final String[] base;
        private final String[] ours;
        private final String[] theirs;
        private final int[] toOurs;
        private final int[] toTheirs;

        private final List<String> merged = new ArrayList<>();
        private final List<ConflictRegion> regions = new ArrayList<>();

        private int b;
        private int o;
        private int t;

        Walk(String[] base, String[] ours, String[] theirs, int[] toOurs, int[] toTheirs) {
            this.base = base;
            this.ours = ours;
            this.theirs = theirs;
            this.toOurs = toOurs;
            this.toTheirs = toTheirs;
        }

        LineMergeResult run(boolean baseNewline, boolean ourNewline, boolean theirNewline) {
            while (true) {
                takeStableLines();
                if (b >= base.length && o >= ours.length && t >= theirs.length) {
                    break;
                }
                resolveChunk();
            }

            if (!regions.isEmpty()) {
                return new LineMergeResult.Conflicted(regions);
            }
            return new LineMergeResult.Clean(
                    join(merged, mergedNewline(baseNewline, ourNewline, theirNewline)));
        }

        /**
         * Passes through lines all three agree on.
         *
         * <p>Agreement is positional as well as textual: a base line matched by
         * both sides only counts while both sides have reached it. A line that
         * matches further ahead is the far end of a chunk, not part of one.
         */
        private void takeStableLines() {
            while (b < base.length && toOurs[b] == o && toTheirs[b] == t) {
                merged.add(base[b]);
                b++;
                o++;
                t++;
            }
        }

        /** Decides the stretch running up to the next line all three share. */
        private void resolveChunk() {
            int endBase = base.length;
            int endOurs = ours.length;
            int endTheirs = theirs.length;

            for (int candidate = b; candidate < base.length; candidate++) {
                // Both sides must still be able to reach it: a match behind
                // where one side already stands cannot close this chunk.
                if (toOurs[candidate] >= o && toTheirs[candidate] >= t) {
                    endBase = candidate;
                    endOurs = toOurs[candidate];
                    endTheirs = toTheirs[candidate];
                    break;
                }
            }

            List<String> baseChunk = slice(base, b, endBase);
            List<String> ourChunk = slice(ours, o, endOurs);
            List<String> theirChunk = slice(theirs, t, endTheirs);

            if (ourChunk.equals(theirChunk)) {
                merged.addAll(ourChunk);
            } else if (ourChunk.equals(baseChunk)) {
                merged.addAll(theirChunk);
            } else if (theirChunk.equals(baseChunk)) {
                merged.addAll(ourChunk);
            } else {
                regions.add(new ConflictRegion(
                        range(b, endBase), range(o, endOurs), range(t, endTheirs)));
            }

            b = endBase;
            o = endOurs;
            t = endTheirs;
        }
    }

    /**
     * Whether the merged file ends with a newline.
     *
     * <p>The same three-way question as any other, over one bit. Splitting text
     * into lines cannot represent it — "a\n" and "a" are both one line — so it
     * is decided separately rather than lost. Whichever side left it as the base
     * had it did not change it, so the other side's answer stands; when the
     * sides agree there is nothing to decide.
     */
    private static boolean mergedNewline(boolean base, boolean ours, boolean theirs) {
        if (ours == theirs) {
            return ours;
        }
        return ours == base ? theirs : ours;
    }

    private static boolean endsWithNewline(String text) {
        return text.endsWith("\n");
    }

    private static String join(List<String> lines, boolean trailingNewline) {
        if (lines.isEmpty()) {
            return "";
        }
        return String.join("\n", lines) + (trailingNewline ? "\n" : "");
    }

    private static List<String> slice(String[] source, int from, int to) {
        return List.of(source).subList(from, to);
    }

    /** A half-open, one-based range from zero-based line indexes. */
    private static LineRange range(int from, int to) {
        return new LineRange(from + 1, to + 1);
    }
}
