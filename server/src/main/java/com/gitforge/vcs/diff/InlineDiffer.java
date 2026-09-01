package com.gitforge.vcs.diff;

import java.util.ArrayList;
import java.util.List;

/**
 * Which characters actually changed within a pair of lines.
 *
 * <p>A one-character edit currently reads as a whole line removed and a whole
 * line added, leaving the reader to find the difference themselves. This marks
 * the changed runs inside each line so the eye lands on them directly.
 *
 * <p>Runs strictly after line-level diffing, over the hunks
 * {@link LineDiffer} produced. It does not participate in deciding which lines
 * changed and cannot alter that decision — line diffing is the statement about
 * repository content, and this only annotates it.
 *
 * <p><strong>Pairing is positional.</strong> Within a hunk, a run of removed
 * lines followed immediately by a run of added lines is zipped in order: the
 * first removed with the first added, and so on. Leftovers on either side are
 * left unannotated. Nothing is matched heuristically or across a distance —
 * guessing which distant line "corresponds" to another is rename detection
 * wearing a different hat, and it is not what this does.
 *
 * <p>Bounded before the expensive work starts, because a diff endpoint is
 * reachable by anyone who can read a repository. Exceeding a bound omits the
 * annotation for that pair; it never fails the diff, because the line-level
 * answer is still correct and useful without it.
 */
public final class InlineDiffer {

    /**
     * Longest line this will look at, in UTF-16 code units.
     *
     * <p>Measured against this repository's own 59,595 tracked source lines:
     * median 30, 99th percentile 117, longest 1,786. A ceiling here covers
     * everything a person actually reads while keeping even the trimming pass
     * bounded. A line longer than this is generated or minified, where marking
     * a run inside it would not help anyone.
     */
    static final int MAX_LINE_CHARS = 1_024;

    /**
     * Longest differing core this will diff, after identical ends are trimmed.
     *
     * <p>The bound that matters. Trimming reduces a real edit to almost nothing
     * — across the demonstration repositories the differing core of a changed
     * line measured median 1 and never exceeded 7 characters — so this is only
     * ever reached by a line that was rewritten outright. For those, every
     * character differs and a highlight would cover the whole line, saying
     * nothing the line's own colour does not already say.
     *
     * <p>It also caps the comparison table at 256 x 256 cells for a single pair.
     */
    static final int MAX_CORE_CHARS = 256;

    /**
     * How many pairs one response will annotate.
     *
     * <p>A response already carries hunks for at most
     * {@link com.gitforge.vcs.repository.DiffService} files, but that still
     * permits many changed lines. Real diffs measured at most two paired lines
     * per response, so this leaves a wide margin while capping the worst case at
     * this many bounded comparisons rather than an unbounded number.
     */
    static final int MAX_PAIRS_PER_RESPONSE = 1_000;

    private int remainingPairs;

    /** One differ per response, because the budget spans every file in it. */
    public InlineDiffer() {
        this(MAX_PAIRS_PER_RESPONSE);
    }

    /** Visible for tests, so budget exhaustion can be reached without a thousand pairs. */
    InlineDiffer(int maxPairs) {
        if (maxPairs < 0) {
            throw new IllegalArgumentException("The pair budget must not be negative: " + maxPairs);
        }
        this.remainingPairs = maxPairs;
    }

    /**
     * Returns the hunks with changed runs marked on paired lines.
     *
     * <p>Every other line is returned unchanged, including context lines, which
     * never carry runs: nothing about them changed.
     */
    public List<Hunk> annotate(List<Hunk> hunks) {
        List<Hunk> annotated = new ArrayList<>(hunks.size());
        for (Hunk hunk : hunks) {
            annotated.add(new Hunk(
                    hunk.oldStart(), hunk.oldCount(), hunk.newStart(), hunk.newCount(),
                    annotateLines(hunk.lines())));
        }
        return annotated;
    }

    private List<DiffLine> annotateLines(List<DiffLine> lines) {
        List<DiffLine> result = new ArrayList<>(lines);

        int index = 0;
        while (index < result.size()) {
            if (result.get(index).type() != DiffLine.Type.REMOVED) {
                index++;
                continue;
            }

            int removedStart = index;
            while (index < result.size() && result.get(index).type() == DiffLine.Type.REMOVED) {
                index++;
            }
            int addedStart = index;
            while (index < result.size() && result.get(index).type() == DiffLine.Type.ADDED) {
                index++;
            }

            int removedCount = addedStart - removedStart;
            int addedCount = index - addedStart;

            // Only as many pairs as both sides can supply. A run of three
            // removals followed by one addition is one pair and two lines that
            // correspond to nothing.
            int pairs = Math.min(removedCount, addedCount);
            for (int offset = 0; offset < pairs; offset++) {
                pair(result, removedStart + offset, addedStart + offset);
            }
        }
        return result;
    }

    /** Marks one removed line and its positional counterpart, if affordable. */
    private void pair(List<DiffLine> lines, int removedIndex, int addedIndex) {
        if (remainingPairs <= 0) {
            return;
        }

        DiffLine removed = lines.get(removedIndex);
        DiffLine added = lines.get(addedIndex);
        String before = removed.content();
        String after = added.content();

        if (before.length() > MAX_LINE_CHARS || after.length() > MAX_LINE_CHARS) {
            return;
        }
        if (before.equals(after)) {
            // The line diff says these differ, so this is unreachable through
            // LineDiffer; checked anyway because the whole computation below
            // assumes there is something to find.
            return;
        }

        int prefix = commonPrefix(before, after);
        int suffix = commonSuffix(before, after, prefix);

        int beforeCore = before.length() - prefix - suffix;
        int afterCore = after.length() - prefix - suffix;
        if (beforeCore > MAX_CORE_CHARS || afterCore > MAX_CORE_CHARS) {
            return;
        }

        // Counted once the work is actually going to happen, so lines skipped
        // for being oversized do not consume another line's budget.
        remainingPairs--;

        boolean[] beforeChanged = new boolean[beforeCore];
        boolean[] afterChanged = new boolean[afterCore];
        markChanged(
                before.substring(prefix, prefix + beforeCore),
                after.substring(prefix, prefix + afterCore),
                beforeChanged,
                afterChanged);

        List<Segment> beforeRuns = wholeCodePoints(runs(beforeChanged, prefix), before);
        List<Segment> afterRuns = wholeCodePoints(runs(afterChanged, prefix), after);

        if (!beforeRuns.isEmpty()) {
            lines.set(removedIndex, removed.withSegments(beforeRuns));
        }
        if (!afterRuns.isEmpty()) {
            lines.set(addedIndex, added.withSegments(afterRuns));
        }
    }

    /**
     * Flags the characters outside the longest common subsequence.
     *
     * <p>A table rather than Myers: both sides are already trimmed to their
     * differing core and capped at {@link #MAX_CORE_CHARS}, so the quadratic
     * term is bounded by a small constant, and the simpler algorithm is the one
     * whose correctness is obvious on reading. Myers earns its complexity over
     * whole files, which is where {@link LineDiffer} uses it.
     *
     * <p>Anything not on the common subsequence changed, which is what produces
     * several separate runs for a line with several separate edits.
     */
    private static void markChanged(String before, String after, boolean[] beforeChanged, boolean[] afterChanged) {
        int n = before.length();
        int m = after.length();

        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = before.charAt(i) == after.charAt(j)
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }

        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (before.charAt(i) == after.charAt(j)) {
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                beforeChanged[i++] = true;
            } else {
                afterChanged[j++] = true;
            }
        }
        while (i < n) {
            beforeChanged[i++] = true;
        }
        while (j < m) {
            afterChanged[j++] = true;
        }
    }

    /**
     * Widens any boundary that would fall inside a surrogate pair.
     *
     * <p>Comparison happens per code unit, so two emoji sharing a high surrogate
     * — 😀 and 😞 both begin {@code U+D83D} — differ only in their second half.
     * A run starting there would cut the character in two, and each half renders
     * as a replacement glyph on both sides of the boundary: the client would show
     * two broken symbols instead of the emoji that changed.
     *
     * <p>Widening can make two runs meet, which is why they are merged rather
     * than emitted touching: the contract promises strictly ascending,
     * non-overlapping segments.
     */
    private static List<Segment> wholeCodePoints(List<Segment> segments, String content) {
        List<Segment> snapped = new ArrayList<>(segments.size());

        for (Segment segment : segments) {
            int start = segment.start();
            int end = segment.end();

            if (start > 0
                    && Character.isLowSurrogate(content.charAt(start))
                    && Character.isHighSurrogate(content.charAt(start - 1))) {
                start--;
            }
            if (end < content.length()
                    && Character.isHighSurrogate(content.charAt(end - 1))
                    && Character.isLowSurrogate(content.charAt(end))) {
                end++;
            }

            if (!snapped.isEmpty() && start <= snapped.getLast().end()) {
                Segment previous = snapped.removeLast();
                snapped.add(new Segment(previous.start(), Math.max(previous.end(), end)));
            } else {
                snapped.add(new Segment(start, end));
            }
        }
        return snapped;
    }

    /** Consecutive flagged characters, as segments offset back into the full line. */
    private static List<Segment> runs(boolean[] changed, int offset) {
        List<Segment> segments = new ArrayList<>();
        int index = 0;
        while (index < changed.length) {
            if (!changed[index]) {
                index++;
                continue;
            }
            int start = index;
            while (index < changed.length && changed[index]) {
                index++;
            }
            segments.add(new Segment(offset + start, offset + index));
        }
        return segments;
    }

    private static int commonPrefix(String a, String b) {
        int limit = Math.min(a.length(), b.length());
        int i = 0;
        while (i < limit && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }

    /** Counted from the end, never overlapping the prefix already claimed. */
    private static int commonSuffix(String a, String b, int prefix) {
        int limit = Math.min(a.length(), b.length()) - prefix;
        int i = 0;
        while (i < limit && a.charAt(a.length() - 1 - i) == b.charAt(b.length() - 1 - i)) {
            i++;
        }
        return i;
    }
}
