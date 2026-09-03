package com.gitforge.vcs.ref;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The relative part of a revision: the {@code ^} and {@code ~} steps that walk
 * back from a commit someone already named.
 *
 * <p>Only the grammar lives here. Which commit the walk starts from, and what
 * each step means against the graph, belong to {@link BranchService} — this is
 * the one place revisions are resolved, and splitting that in two is how the
 * two halves start disagreeing.
 *
 * <p>The grammar is small and total:
 *
 * <pre>
 *   suffixes := step+
 *   step     := ('^' | '~') digits?
 * </pre>
 *
 * <p>A step with no digits means one, so {@code ^} is {@code ^1} and {@code ~}
 * is {@code ~1}. Zero is allowed and means the commit itself, which makes
 * {@code main~0} a long way of writing {@code main} rather than an error.
 *
 * <p>Anything the grammar cannot read is <em>malformed</em> rather than absent,
 * and the difference matters: {@code HEAD~abc} is a request nobody can answer,
 * while {@code nosuchbranch~1} is a perfectly sensible request about something
 * that is not there. The caller reports the first as a bad request and the
 * second as not found.
 */
final class RevisionSuffix {

    /**
     * How many steps one expression may chain.
     *
     * <p>Each step is a graph walk, so this bounds the work a single revision
     * can ask for. Real expressions are one or two steps; the deepest
     * first-parent chain in this repository's own history is 36 commits, and the
     * longest demonstration history is 60. Sixty-four leaves every reachable
     * commit addressable by a chain of single steps and still refuses an
     * expression built to be expensive rather than to mean something.
     */
    static final int MAX_STEPS = 64;

    /**
     * The largest count one step may carry.
     *
     * <p>Generously past the 126 commits in this repository and the 60 of the
     * longest demonstration history, so no reachable commit is out of reach,
     * while keeping the parsed value small enough to reason about. A larger
     * number is refused as malformed rather than silently walking to the root
     * and reporting nothing found — the two are different answers.
     */
    static final int MAX_COUNT = 100_000;

    /** One step of a walk: a parent by position, or an ancestor by generation. */
    record Step(Kind kind, int count) {

        enum Kind {
            /** {@code ^n} — the n-th parent, counting from one. */
            PARENT,
            /** {@code ~n} — n generations back, always by first parent. */
            ANCESTOR
        }
    }

    private RevisionSuffix() {
    }

    /** True where a step could begin, which is where a base name must end. */
    static boolean isStepStart(char character) {
        return character == '^' || character == '~';
    }

    /**
     * Reads a chain of steps.
     *
     * @param suffix the part of a revision from its first step onwards
     * @return the steps in order, or empty if this is not a chain of steps at all
     */
    static Optional<List<Step>> parse(String suffix) {
        if (suffix == null || suffix.isEmpty() || !isStepStart(suffix.charAt(0))) {
            return Optional.empty();
        }

        List<Step> steps = new ArrayList<>();
        int index = 0;

        while (index < suffix.length()) {
            char marker = suffix.charAt(index);
            if (!isStepStart(marker)) {
                return Optional.empty();
            }
            index++;

            int digitsStart = index;
            while (index < suffix.length() && Character.isDigit(suffix.charAt(index))) {
                index++;
            }

            // Guarded before parsing rather than after: a run of digits long
            // enough to overflow an int must not be turned into one first.
            String digits = suffix.substring(digitsStart, index);
            if (digits.length() > String.valueOf(MAX_COUNT).length()) {
                return Optional.empty();
            }

            int count = digits.isEmpty() ? 1 : Integer.parseInt(digits);
            if (count > MAX_COUNT) {
                return Optional.empty();
            }
            if (steps.size() == MAX_STEPS) {
                return Optional.empty();
            }

            steps.add(new Step(
                    marker == '^' ? Step.Kind.PARENT : Step.Kind.ANCESTOR, count));
        }
        return Optional.of(List.copyOf(steps));
    }
}
