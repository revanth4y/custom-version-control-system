package com.gitforge.vcs.scale;

import com.gitforge.vcs.graph.CommitGraph;
import com.gitforge.vcs.insights.BranchDivergence;
import com.gitforge.vcs.insights.RefComposition;
import com.gitforge.vcs.object.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixture F — how reference count and history depth cost when combined.
 *
 * <p>The V2.0.16 suite measured each dimension on its own: ten thousand commits
 * on one branch, and five thousand references over a five-commit history. Both
 * looked affordable. Neither could see the product, and the product is what a
 * real repository has.
 *
 * <p><strong>What this exposes.</strong> {@code BranchDivergence.against} calls
 * {@code graph.ancestorsOf(tip)} once per branch, and {@code CommitGraph} keeps
 * no memo, so each call walks the history again from the tip — reading, inflating
 * and re-hashing every commit on the way. The cost is therefore
 * O(branches x history), and the same shape appears in
 * {@code RefComposition.commitsOnlyTagsProtect}, which walks once per branch,
 * once per remote reference and once per tag.
 *
 * <p><strong>Why it measures a curve rather than the worst case.</strong> Running
 * divergence over two thousand branches against a ten-thousand-commit history
 * means twenty million commit reads. On the CI machine that is minutes; on a
 * Windows developer machine it is hours. A fixture nobody can run is a fixture
 * nobody runs, so this measures the cost at several branch counts against the
 * full-depth history and reports the slope. The projection to full scale is
 * printed, and clearly labelled as a projection — it is arithmetic on measured
 * numbers, not a measurement.
 *
 * <p>Deliberately no optimisation accompanies this fixture. It exists to record
 * what the code does today, so that a later change has something to be compared
 * against.
 */
class RefsByDeepHistoryIT {

    /** Deep enough that per-branch traversal dominates per-branch overhead. */
    private static final int HISTORY = 10_000;

    /**
     * Branch counts to measure at.
     *
     * <p>The first five are the counts the V2.0.16 baseline used, kept so the
     * before and after are the same measurement rather than two different ones.
     * The rest are only affordable since divergence stopped replaying the whole
     * history per reference - at the old cost, 2,000 references against this
     * history was about two and three quarter hours.
     */
    private static final int[] BRANCH_STEPS = {1, 5, 10, 20, 40, 200, 1_000, 2_000};

    /** The reference count the suite must reach, measured on the operations that scale with it. */
    private static final int FULL_BRANCHES = 2_000;
    private static final int FULL_TAGS = 2_000;

    /**
     * Generous but real. The Windows measurements this bound has to accommodate
     * are roughly fifty times the CI ones; a bound that fits CI alone would turn
     * a slow machine into a failure that says nothing about the code.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    @DisplayName("F: references against a deep history")
    void refsByDeepHistory(@TempDir Path parent) throws IOException {
        System.out.println("\n=== F: refs x deep history ===");

        ScaleFixtures.Fixture fixture = ScaleFixtures.repository(parent, "deep", "main");

        ObjectId[] tip = new ObjectId[1];
        long setup = ScaleFixtures.timed(
                "setup: " + HISTORY + " commits",
                () -> tip[0] = ScaleFixtures.linearHistory(fixture, "main", HISTORY));
        ScaleFixtures.note("commits", HISTORY);
        ScaleFixtures.note("objects stored", fixture.repository().objects().count());
        ScaleFixtures.note("per-commit setup cost (ms)", String.format("%.2f", setup / (double) HISTORY));

        // A branch part-way down the history, so the divergence numbers have
        // something to be right about. Without it every branch sits at the tip
        // and "ahead 0, behind 0" would pass whether the walk worked or not.
        ObjectId midpoint = fixture.repository().reader()
                .resolve("main~" + (HISTORY / 2))
                .orElseThrow(() -> new AssertionError("the history is not as deep as it should be"));
        fixture.repository().branches().createBranch("midpoint", midpoint);

        // ---------------------------------------------------------- divergence
        System.out.println("  branch divergence against HEAD, history fixed at " + HISTORY + ":");
        long previous = 0;
        int previousBranches = 0;
        long lastPerBranch = 0;
        for (int branches : BRANCH_STEPS) {
            ScaleFixtures.branches(fixture, tip[0], "branch-", previousBranches, branches);
            previousBranches = branches;

            // Rebuilt each time on purpose: a graph carried between measurements
            // would be measuring a cache this code does not have.
            BranchDivergence divergence = new BranchDivergence(
                    fixture.repository().refs(),
                    fixture.repository().branches(),
                    new CommitGraph(fixture.repository().objects()));

            List<BranchDivergence.Branch>[] rows = new List[1];
            ScaleFixtures.resetPeakHeap();
            long started = System.nanoTime();
            rows[0] = divergence.againstHead();
            long millis = (System.nanoTime() - started) / 1_000_000;

            // main + midpoint + the branch-N series.
            int refs = rows[0].size();
            long perBranch = millis / Math.max(refs, 1);
            System.out.printf(
                    "    %4d refs  %8d ms  %6d ms/ref  peak heap %5d MB%s%n",
                    refs, millis, perBranch, ScaleFixtures.peakHeapMb(),
                    previous == 0 ? ""
                            : String.format("   (x%.2f for x%.1f refs)",
                                    millis / (double) previous, refs / (double) (previousBranches)));
            previous = millis;
            lastPerBranch = perBranch;

            // Correctness travels with the measurement. A fast wrong answer is
            // not a baseline worth optimising against.
            BranchDivergence.Branch mid = rows[0].stream()
                    .filter(row -> row.name().equals("midpoint"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("midpoint branch missing"));
            assertThat(mid.behind())
                    .as("midpoint sits half the history below the tip")
                    .isEqualTo(HISTORY / 2);
            assertThat(mid.ahead()).as("midpoint is a pure ancestor").isZero();
            assertThat(mid.related()).isTrue();
        }

        // No projection any more: the largest step above is the full reference
        // count, measured. The V2.0.16 baseline could only extrapolate to it,
        // because reaching it would have taken hours.
        System.out.printf("    measured to %d refs; V2.0.16 could only project to it%n",
                BRANCH_STEPS[BRANCH_STEPS.length - 1]);

        // ------------------------------------------------------- composition
        System.out.println("  reference composition, history fixed at " + HISTORY + ":");
        int tagsSoFar = 0;
        for (int extraTags : new int[] {0, 20, 480, 1_500}) {
            // Ranges must not overlap: creating tag-0 twice is a duplicate-name
            // refusal, not a measurement.
            ScaleFixtures.tags(fixture, tip[0], "tag-", tagsSoFar, tagsSoFar + extraTags);
            tagsSoFar += extraTags;
            RefComposition composition = new RefComposition(
                    fixture.repository().refs(),
                    fixture.repository().objects(),
                    new CommitGraph(fixture.repository().objects()));
            int tagCount = tagsSoFar;
            ScaleFixtures.timed(
                    String.format("    %d branches + %d tags", previousBranches + 2, tagCount),
                    composition::compute);
        }

        // ------------------------------------------- full reference count
        // The operations that scale with reference count alone stay affordable
        // at full scale, so they are measured there rather than projected.
        System.out.println("  at full reference scale:");
        final int measuredBranches = previousBranches;
        if (measuredBranches < FULL_BRANCHES) {
            ScaleFixtures.timed(
                    "setup: grow to " + FULL_BRANCHES + " branches",
                    () -> ScaleFixtures.branches(
                            fixture, tip[0], "branch-", measuredBranches, FULL_BRANCHES));
        }
        final int measuredTags = tagsSoFar;
        if (measuredTags < FULL_TAGS) {
            ScaleFixtures.timed(
                    "setup: grow to " + FULL_TAGS + " tags",
                    () -> ScaleFixtures.tags(fixture, tip[0], "tag-", measuredTags, FULL_TAGS));
        }

        long[] branchCount = new long[1];
        ScaleFixtures.timed("list branches",
                () -> branchCount[0] = fixture.repository().refs().listBranches().size());
        long[] tagCount = new long[1];
        ScaleFixtures.timed("list tags",
                () -> tagCount[0] = fixture.repository().refs().listTags().size());
        ScaleFixtures.timed("resolve one branch by name",
                () -> fixture.repository().refs().getBranch("branch-1999"));
        ScaleFixtures.timed("resolve one tag by name",
                () -> fixture.repository().refs().getTag("tag-1999"));

        ScaleFixtures.note("branches", branchCount[0]);
        ScaleFixtures.note("tags", tagCount[0]);
        ScaleFixtures.note("references total", branchCount[0] + tagCount[0]);
        ScaleFixtures.note("objects stored", fixture.repository().objects().count());

        assertThat(branchCount[0])
                .as("main, midpoint and the branch- series")
                .isEqualTo(FULL_BRANCHES + 2);
        assertThat(tagCount[0]).isEqualTo(FULL_TAGS);
        assertThat(branchCount[0] + tagCount[0])
                .as("thousands of references, as the fixture requires")
                .isGreaterThan(4_000);
    }
}
