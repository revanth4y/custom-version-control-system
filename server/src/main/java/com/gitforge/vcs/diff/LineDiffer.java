package com.gitforge.vcs.diff;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Line-level differences between two texts, using Myers' O(ND) algorithm.
 *
 * <p>Diffing belongs in the version-control engine rather than in a client: it is
 * a statement about repository content, and every caller must get the same
 * answer for the same pair of blobs.
 *
 * <p>Three stages:
 *
 * <ol>
 *   <li><strong>Trim.</strong> Identical leading and trailing lines are removed
 *       first. Real edits touch a small part of a file, so this usually reduces
 *       the problem to a handful of lines and makes the expensive stage cheap.</li>
 *   <li><strong>Myers.</strong> The shortest edit script over what remains. The
 *       algorithm's cost grows with the number of <em>differences</em>, not the
 *       size of the files, which is why trimming first matters so much.</li>
 *   <li><strong>Group.</strong> Changes are gathered into hunks with three lines
 *       of context, merging any whose context regions overlap.</li>
 * </ol>
 *
 * <p>Every stage is bounded. A diff endpoint is reachable by anyone who can read
 * a repository, so an unbounded computation would be a denial-of-service
 * surface: oversized inputs are refused rather than attempted.
 */
public final class LineDiffer {

    /** Unchanged lines kept either side of a change. */
    private static final int CONTEXT_LINES = 3;

    /** Beyond this many lines on either side, the file is not diffed. */
    static final int MAX_LINES = 20_000;

    /**
     * Beyond this many differences the result stops being useful to read, and
     * the trace needed to recover it grows quadratically.
     */
    static final int MAX_EDIT_DISTANCE = 5_000;

    /** The entry in an alignment for a line the other side does not have. */
    public static final int UNMATCHED = -1;

    private LineDiffer() {
    }

    /**
     * Which line of {@code newLines} each line of {@code oldLines} corresponds to.
     *
     * <p>The same Myers computation as {@link #diff}, reported as the raw
     * correspondence rather than as hunks. Hunks describe a change for a reader
     * and deliberately drop the unchanged regions between them; a three-way
     * merge needs precisely those regions, because a line both sides left alone
     * is what tells it their edits do not overlap.
     *
     * <p>The returned array has one entry per old line: the index of the line it
     * matches in {@code newLines}, or {@link #UNMATCHED}. Entries are strictly
     * increasing where they are matched, since an alignment cannot cross itself.
     * The array is freshly allocated and belongs to the caller.
     *
     * @return the alignment, or empty if the inputs exceed the bounds above
     */
    public static Optional<int[]> align(String[] oldLines, String[] newLines) {
        if (oldLines.length > MAX_LINES || newLines.length > MAX_LINES) {
            return Optional.empty();
        }

        int prefix = commonPrefix(oldLines, newLines);
        int suffix = commonSuffix(oldLines, newLines, prefix);

        Optional<List<Edit>> script = shortestEditScript(
                slice(oldLines, prefix, oldLines.length - suffix),
                slice(newLines, prefix, newLines.length - suffix));
        if (script.isEmpty()) {
            return Optional.empty();
        }

        int[] matched = new int[oldLines.length];
        Arrays.fill(matched, UNMATCHED);

        // The trimmed ends are identical by definition, so they match position
        // for position without the algorithm having to say so.
        for (int i = 0; i < prefix; i++) {
            matched[i] = i;
        }
        for (int i = 0; i < suffix; i++) {
            matched[oldLines.length - 1 - i] = newLines.length - 1 - i;
        }
        for (Edit edit : script.get()) {
            if (edit.type() == DiffLine.Type.CONTEXT) {
                matched[prefix + edit.oldIndex()] = prefix + edit.newIndex();
            }
        }
        return Optional.of(matched);
    }

    /**
     * Computes hunks between two texts.
     *
     * @return the hunks, or empty if the inputs are too large to diff
     */
    public static Optional<List<Hunk>> diff(String oldText, String newText) {
        String[] oldLines = com.gitforge.vcs.object.TextContent.lines(oldText);
        String[] newLines = com.gitforge.vcs.object.TextContent.lines(newText);

        if (oldLines.length > MAX_LINES || newLines.length > MAX_LINES) {
            return Optional.empty();
        }

        // Common prefix and suffix are identical by definition, so they can only
        // ever become context. Removing them shrinks the search dramatically.
        int prefix = commonPrefix(oldLines, newLines);
        int suffix = commonSuffix(oldLines, newLines, prefix);

        String[] oldCore = slice(oldLines, prefix, oldLines.length - suffix);
        String[] newCore = slice(newLines, prefix, newLines.length - suffix);

        Optional<List<Edit>> script = shortestEditScript(oldCore, newCore);
        if (script.isEmpty()) {
            return Optional.empty();
        }

        List<DiffLine> lines = numbered(script.get(), oldCore, newCore, prefix);
        return Optional.of(groupIntoHunks(lines, oldLines, newLines, prefix, suffix));
    }

    /** One step of the edit script. */
    private record Edit(DiffLine.Type type, int oldIndex, int newIndex) {
    }

    /**
     * Myers' greedy algorithm.
     *
     * <p>Walks increasing edit distances {@code d}, tracking the furthest point
     * reachable on each diagonal {@code k}. The first {@code d} that reaches the
     * bottom-right corner is the shortest edit distance, and the recorded traces
     * are then walked backwards to recover the path that got there.
     *
     * @return the edit script, or empty if the texts differ too much to diff
     */
    private static Optional<List<Edit>> shortestEditScript(String[] oldLines, String[] newLines) {
        int n = oldLines.length;
        int m = newLines.length;

        if (n == 0 && m == 0) {
            return Optional.of(List.of());
        }

        int max = Math.min(n + m, MAX_EDIT_DISTANCE);
        int offset = max;
        int[] v = new int[2 * max + 2];
        List<int[]> trace = new ArrayList<>();

        for (int d = 0; d <= max; d++) {
            trace.add(v.clone());

            for (int k = -d; k <= d; k += 2) {
                int index = k + offset;
                if (index < 0 || index + 1 >= v.length) {
                    continue;
                }
                // Move down (an insertion) when that reaches further, otherwise
                // move right (a deletion).
                int x = (k == -d || (k != d && v[index - 1] < v[index + 1]))
                        ? v[index + 1]
                        : v[index - 1] + 1;
                int y = x - k;

                // Follow the diagonal for as long as the lines agree: identical
                // lines are free.
                while (x < n && y < m && oldLines[x].equals(newLines[y])) {
                    x++;
                    y++;
                }
                v[index] = x;

                if (x >= n && y >= m) {
                    return Optional.of(backtrack(trace, d, offset, n, m, oldLines, newLines));
                }
            }
        }
        // The edit distance exceeded the cap.
        return Optional.empty();
    }

    /** Walks the recorded traces backwards to recover the edit path. */
    private static List<Edit> backtrack(
            List<int[]> trace, int distance, int offset, int n, int m,
            String[] oldLines, String[] newLines) {

        List<Edit> edits = new ArrayList<>();
        int x = n;
        int y = m;

        for (int d = distance; d > 0; d--) {
            int[] v = trace.get(d);
            int k = x - y;
            int index = k + offset;

            boolean movedDown = k == -d || (k != d && v[index - 1] < v[index + 1]);
            int previousK = movedDown ? k + 1 : k - 1;
            int previousX = v[previousK + offset];
            int previousY = previousX - previousK;

            // Diagonal moves are unchanged lines.
            while (x > previousX && y > previousY) {
                edits.add(new Edit(DiffLine.Type.CONTEXT, x - 1, y - 1));
                x--;
                y--;
            }
            if (movedDown) {
                edits.add(new Edit(DiffLine.Type.ADDED, -1, y - 1));
                y--;
            } else {
                edits.add(new Edit(DiffLine.Type.REMOVED, x - 1, -1));
                x--;
            }
        }
        // Anything left is the leading run of identical lines.
        while (x > 0 && y > 0) {
            edits.add(new Edit(DiffLine.Type.CONTEXT, x - 1, y - 1));
            x--;
            y--;
        }

        Collections.reverse(edits);
        return edits;
    }

    /** Turns edits into lines numbered against the untrimmed files. */
    private static List<DiffLine> numbered(
            List<Edit> script, String[] oldCore, String[] newCore, int prefix) {

        List<DiffLine> lines = new ArrayList<>(script.size());
        for (Edit edit : script) {
            switch (edit.type()) {
                case CONTEXT -> lines.add(DiffLine.context(
                        prefix + edit.oldIndex() + 1,
                        prefix + edit.newIndex() + 1,
                        oldCore[edit.oldIndex()]));
                case ADDED -> lines.add(DiffLine.added(
                        prefix + edit.newIndex() + 1, newCore[edit.newIndex()]));
                case REMOVED -> lines.add(DiffLine.removed(
                        prefix + edit.oldIndex() + 1, oldCore[edit.oldIndex()]));
            }
        }
        return lines;
    }

    /**
     * Gathers changes into hunks, restoring context from the trimmed regions.
     *
     * <p>Changes closer together than twice the context window land in one hunk
     * rather than two hunks that would overlap.
     */
    private static List<Hunk> groupIntoHunks(
            List<DiffLine> coreLines, String[] oldLines, String[] newLines, int prefix, int suffix) {

        List<Integer> changeIndexes = new ArrayList<>();
        for (int i = 0; i < coreLines.size(); i++) {
            if (coreLines.get(i).type() != DiffLine.Type.CONTEXT) {
                changeIndexes.add(i);
            }
        }
        if (changeIndexes.isEmpty()) {
            return List.of();
        }

        List<Hunk> hunks = new ArrayList<>();
        int groupStart = 0;
        while (groupStart < changeIndexes.size()) {
            int groupEnd = groupStart;
            while (groupEnd + 1 < changeIndexes.size()
                    && changeIndexes.get(groupEnd + 1) - changeIndexes.get(groupEnd) <= CONTEXT_LINES * 2) {
                groupEnd++;
            }

            int firstChange = changeIndexes.get(groupStart);
            int lastChange = changeIndexes.get(groupEnd);
            int from = Math.max(0, firstChange - CONTEXT_LINES);
            int to = Math.min(coreLines.size() - 1, lastChange + CONTEXT_LINES);

            List<DiffLine> body = new ArrayList<>(coreLines.subList(from, to + 1));

            // Context that trimming removed still belongs in the hunk. A change
            // at either edge of the core would otherwise be shown with no
            // surrounding lines at all, even though they exist in both files.
            prependTrimmedPrefix(body, oldLines, prefix, from);
            appendTrimmedSuffix(body, oldLines, newLines, suffix, coreLines.size() - 1 - to);

            hunks.add(toHunk(body));
            groupStart = groupEnd + 1;
        }
        return hunks;
    }

    /** Restores leading context removed by prefix trimming. */
    private static void prependTrimmedPrefix(
            List<DiffLine> body, String[] oldLines, int prefix, int coreLinesBefore) {

        int needed = Math.min(CONTEXT_LINES - coreLinesBefore, prefix);
        for (int i = 1; i <= needed; i++) {
            // Prefix lines occupy the same position in both files.
            int number = prefix - i + 1;
            body.add(0, DiffLine.context(number, number, oldLines[number - 1]));
        }
    }

    /**
     * Restores trailing context removed by suffix trimming.
     *
     * <p>Unlike the prefix, suffix lines sit at different offsets in the two
     * files whenever their lengths differ, so both numbers are computed
     * separately.
     */
    private static void appendTrimmedSuffix(
            List<DiffLine> body, String[] oldLines, String[] newLines, int suffix, int coreLinesAfter) {

        int needed = Math.min(CONTEXT_LINES - coreLinesAfter, suffix);
        for (int i = 1; i <= needed; i++) {
            int oldNumber = oldLines.length - suffix + i;
            int newNumber = newLines.length - suffix + i;
            body.add(DiffLine.context(oldNumber, newNumber, oldLines[oldNumber - 1]));
        }
    }

    private static Hunk toHunk(List<DiffLine> lines) {
        int oldStart = 0;
        int newStart = 0;
        int oldCount = 0;
        int newCount = 0;

        for (DiffLine line : lines) {
            if (line.oldNumber() != null) {
                oldStart = oldStart == 0 ? line.oldNumber() : oldStart;
                oldCount++;
            }
            if (line.newNumber() != null) {
                newStart = newStart == 0 ? line.newNumber() : newStart;
                newCount++;
            }
        }
        return new Hunk(oldStart, oldCount, newStart, newCount, lines);
    }

    private static int commonPrefix(String[] a, String[] b) {
        int limit = Math.min(a.length, b.length);
        int i = 0;
        while (i < limit && a[i].equals(b[i])) {
            i++;
        }
        return i;
    }

    private static int commonSuffix(String[] a, String[] b, int prefix) {
        int limit = Math.min(a.length, b.length) - prefix;
        int i = 0;
        while (i < limit && a[a.length - 1 - i].equals(b[b.length - 1 - i])) {
            i++;
        }
        return i;
    }

    private static String[] slice(String[] source, int from, int to) {
        String[] result = new String[Math.max(0, to - from)];
        System.arraycopy(source, from, result, 0, result.length);
        return result;
    }
}
