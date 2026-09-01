package com.gitforge.vcs.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reconciling one file's lines against the base both sides started from.
 *
 * <p>The properties pinned here are the ones the design rests on: two edits that
 * do not meet merge, two that do stay a conflict, and neither answer depends on
 * which side is called ours.
 */
class LineMergerTest {

    private static String content(LineMergeResult result) {
        assertThat(result).isInstanceOf(LineMergeResult.Clean.class);
        return ((LineMergeResult.Clean) result).content();
    }

    private static List<ConflictRegion> regions(LineMergeResult result) {
        assertThat(result).isInstanceOf(LineMergeResult.Conflicted.class);
        return ((LineMergeResult.Conflicted) result).regions();
    }

    private static LineMergeResult merge(String base, String ours, String theirs) {
        Optional<LineMergeResult> result = LineMerger.merge(base, ours, theirs);
        assertThat(result).isPresent();
        return result.get();
    }

    @Nested
    @DisplayName("edits that do not meet")
    class Clean {

        @Test
        void oursAtTheTopAndTheirsAtTheBottomBothSurvive() {
            String base = "one\ntwo\nthree\nfour\nfive\n";
            String ours = "OURS\ntwo\nthree\nfour\nfive\n";
            String theirs = "one\ntwo\nthree\nfour\nTHEIRS\n";

            // The whole point of the release: a tree merge sees two different
            // file ids and stops, where the lines show the edits never touch.
            assertThat(content(merge(base, ours, theirs)))
                    .isEqualTo("OURS\ntwo\nthree\nfour\nTHEIRS\n");
        }

        @Test
        void severalIndependentEditsOnEachSideAllApply() {
            String base = "a\nb\nc\nd\ne\nf\ng\nh\n";
            String ours = "a\nB\nc\nd\ne\nf\ng\nH\n";
            String theirs = "a\nb\nc\nD\ne\nf\ng\nh\n";

            assertThat(content(merge(base, ours, theirs))).isEqualTo("a\nB\nc\nD\ne\nf\ng\nH\n");
        }

        @Test
        void anInsertionOnOneSideAndAnEditFarFromItBothApply() {
            String base = "one\ntwo\nthree\n";
            String ours = "one\ntwo\ninserted\nthree\n";
            String theirs = "ONE\ntwo\nthree\n";

            assertThat(content(merge(base, ours, theirs)))
                    .isEqualTo("ONE\ntwo\ninserted\nthree\n");
        }

        @Test
        void aDeletionOnOneSideAndAnEditElsewhereBothApply() {
            String base = "keep\ndoomed\nmid\ntail\n";
            String ours = "keep\nmid\ntail\n";
            String theirs = "keep\ndoomed\nmid\nTAIL\n";

            assertThat(content(merge(base, ours, theirs))).isEqualTo("keep\nmid\nTAIL\n");
        }

        @Test
        void identicalEditsOnBothSidesAreNotADisagreement() {
            String base = "one\ntwo\nthree\n";
            String same = "one\nBOTH\nthree\n";

            assertThat(content(merge(base, same, same))).isEqualTo(same);
        }

        @Test
        void anUntouchedSideLeavesTheOtherStanding() {
            String base = "one\ntwo\n";
            String ours = "ONE\ntwo\n";

            assertThat(content(merge(base, ours, base))).isEqualTo(ours);
            assertThat(content(merge(base, base, ours))).isEqualTo(ours);
        }

        @Test
        void anAppendOnOneSideAndAPrependOnTheOtherBothLand() {
            String base = "middle\n";
            String ours = "first\nmiddle\n";
            String theirs = "middle\nlast\n";

            assertThat(content(merge(base, ours, theirs))).isEqualTo("first\nmiddle\nlast\n");
        }

        @Test
        void bothSidesDeletingTheSameLinesAgree() {
            String base = "a\ndoomed\nb\n";
            String without = "a\nb\n";

            assertThat(content(merge(base, without, without))).isEqualTo(without);
        }
    }

    @Nested
    @DisplayName("edits that genuinely collide")
    class Conflicts {

        @Test
        void twoDifferentRewritesOfOneLineConflict() {
            LineMergeResult result = merge("one\ntwo\nthree\n", "one\nOURS\nthree\n", "one\nTHEIRS\nthree\n");

            List<ConflictRegion> regions = regions(result);
            assertThat(regions).singleElement().satisfies(region -> {
                assertThat(region.base()).isEqualTo(new LineRange(2, 3));
                assertThat(region.ours()).isEqualTo(new LineRange(2, 3));
                assertThat(region.theirs()).isEqualTo(new LineRange(2, 3));
            });
        }

        @Test
        void overlappingMultiLineRewritesConflictAsOneRegion() {
            LineMergeResult result = merge(
                    "a\nb\nc\nd\n",
                    "a\nOURS1\nOURS2\nd\n",
                    "a\nTHEIRS1\nTHEIRS2\nd\n");

            assertThat(regions(result)).singleElement().satisfies(region -> {
                assertThat(region.base()).isEqualTo(new LineRange(2, 4));
                assertThat(region.ours()).isEqualTo(new LineRange(2, 4));
                assertThat(region.theirs()).isEqualTo(new LineRange(2, 4));
            });
        }

        @Test
        void oneSideEditingWhatTheOtherDeletedConflicts() {
            LineMergeResult result = merge("a\ntarget\nb\n", "a\nEDITED\nb\n", "a\nb\n");

            // Theirs contributes nothing to the region, which is what a deletion
            // looks like: an empty range rather than a missing one.
            assertThat(regions(result)).singleElement().satisfies(region -> {
                assertThat(region.base()).isEqualTo(new LineRange(2, 3));
                assertThat(region.ours()).isEqualTo(new LineRange(2, 3));
                assertThat(region.theirs().isEmpty()).isTrue();
            });
        }

        @Test
        void differentInsertionsAtTheSamePointConflict() {
            LineMergeResult result = merge("a\nb\n", "a\nours\nb\n", "a\ntheirs\nb\n");

            // Nothing was removed, so the base contributes no lines at all - the
            // two sides are claiming the same gap.
            assertThat(regions(result)).singleElement().satisfies(region -> {
                assertThat(region.base().isEmpty()).isTrue();
                assertThat(region.ours()).isEqualTo(new LineRange(2, 3));
                assertThat(region.theirs()).isEqualTo(new LineRange(2, 3));
            });
        }

        @Test
        void aCollisionDoesNotSuppressTheRestOfTheFile() {
            LineMergeResult result = merge(
                    "a\nb\nc\nd\ne\nf\ng\n",
                    "OURS\nb\nc\nd\ne\nf\nSHARED-OURS\n",
                    "THEIRS\nb\nc\nd\ne\nf\nSHARED-THEIRS\n");

            // Two separate collisions, found in one pass and reported in order.
            assertThat(regions(result)).hasSize(2);
            assertThat(regions(result).get(0).base()).isEqualTo(new LineRange(1, 2));
            assertThat(regions(result).get(1).base()).isEqualTo(new LineRange(7, 8));
        }

        @Test
        void editsOnAdjacentLinesNeedAnUnchangedLineBetweenThem() {
            // A known and deliberate characteristic, not an oversight. Two edits
            // are independent only when an untouched line separates them; with
            // nothing in between there is no evidence they were meant to stand
            // together, and interleaving them would be a guess. Checked against
            // git merge-file, which reports this pair the same way.
            LineMergeResult result = merge("one\ntwo\nthree\n", "ONE\ntwo\nthree\n", "one\nTWO\nthree\n");

            assertThat(result.isClean()).isFalse();
            assertThat(regions(result)).singleElement()
                    .extracting(ConflictRegion::base).isEqualTo(new LineRange(1, 3));

            // One unchanged line apart, and the very same edits merge.
            assertThat(content(merge("one\ngap\ntwo\n", "ONE\ngap\ntwo\n", "one\ngap\nTWO\n")))
                    .isEqualTo("ONE\ngap\nTWO\n");
        }

        @Test
        void aCleanRegionAndACollidingOneCoexist() {
            LineMergeResult result = merge(
                    "top\nmiddle\nbottom\n",
                    "TOP-OURS\nmiddle\nBOTTOM-OURS\n",
                    "top\nmiddle\nBOTTOM-THEIRS\n");

            // The top merged; only the bottom is reported.
            assertThat(regions(result)).singleElement()
                    .extracting(ConflictRegion::base).isEqualTo(new LineRange(3, 4));
        }

        @Test
        void conflictedMergesProduceNoContentAtAll() {
            LineMergeResult result = merge("a\n", "ours\n", "theirs\n");

            assertThat(result.isClean()).isFalse();
            assertThat(result).isNotInstanceOf(LineMergeResult.Clean.class);
        }
    }

    @Nested
    @DisplayName("symmetry")
    class Symmetry {

        @Test
        void aCleanMergeGivesTheSameTextEitherWayRound() {
            String base = "one\ntwo\nthree\nfour\n";
            String ours = "ONE\ntwo\nthree\nfour\n";
            String theirs = "one\ntwo\nthree\nFOUR\n";

            assertThat(content(merge(base, ours, theirs)))
                    .isEqualTo(content(merge(base, theirs, ours)));
        }

        @Test
        void aConflictStaysAConflictEitherWayRound() {
            String base = "one\ntwo\nthree\n";
            String ours = "one\nOURS\nthree\n";
            String theirs = "one\nTHEIRS\nthree\n";

            List<ConflictRegion> forward = regions(merge(base, ours, theirs));
            List<ConflictRegion> reversed = regions(merge(base, theirs, ours));

            assertThat(reversed).hasSameSizeAs(forward);
            assertThat(reversed.getFirst().base()).isEqualTo(forward.getFirst().base());
            // Only the labels swap: what was ours is now theirs.
            assertThat(reversed.getFirst().ours()).isEqualTo(forward.getFirst().theirs());
            assertThat(reversed.getFirst().theirs()).isEqualTo(forward.getFirst().ours());
        }

        @Test
        void anAmbiguousCollisionIsNeverResolvedByPreferringOneSide() {
            // Both sides replaced the same single line with a single line. There
            // is no principled winner, so there must not be one: preferring ours
            // would make the result depend on which branch you stood on.
            String base = "value\n";

            assertThat(merge(base, "ours\n", "theirs\n").isClean()).isFalse();
            assertThat(merge(base, "theirs\n", "ours\n").isClean()).isFalse();
        }

        @Test
        void swappingSidesNeverTurnsAConflictIntoACleanMerge() {
            String base = "a\nb\nc\nd\ne\n";
            String ours = "a\nOURS\nc\nOURS2\ne\n";
            String theirs = "a\nTHEIRS\nc\nd\ne\n";

            assertThat(merge(base, ours, theirs).isClean())
                    .isEqualTo(merge(base, theirs, ours).isClean());
        }
    }

    @Nested
    @DisplayName("determinism")
    class Determinism {

        @Test
        void repeatingAMergeGivesTheSameText() {
            String base = "a\nb\nc\n";
            String ours = "A\nb\nc\n";
            String theirs = "a\nb\nC\n";

            assertThat(content(merge(base, ours, theirs))).isEqualTo(content(merge(base, ours, theirs)));
        }

        @Test
        void repeatingAConflictGivesTheSameRegions() {
            String base = "a\nb\nc\n";
            String ours = "a\nOURS\nc\n";
            String theirs = "a\nTHEIRS\nc\n";

            assertThat(regions(merge(base, ours, theirs))).isEqualTo(regions(merge(base, ours, theirs)));
        }
    }

    @Nested
    @DisplayName("edges")
    class Edges {

        @Test
        void aFileWithNoTrailingNewlineKeepsItThatWay() {
            assertThat(content(merge("one\ntwo", "ONE\ntwo", "one\ntwo"))).isEqualTo("ONE\ntwo");
        }

        @Test
        void addingATrailingNewlineOnOneSideIsItselfAChangeThatCarries() {
            // Splitting into lines cannot represent the final newline, so it is
            // decided by the same three-way rule rather than quietly lost.
            assertThat(content(merge("one", "one\n", "one"))).isEqualTo("one\n");
            assertThat(content(merge("one\n", "one", "one\n"))).isEqualTo("one");
        }

        @Test
        void anEmptyBaseMakesEveryLineAnAddition() {
            // Nothing to measure against: both sides invented the whole file.
            assertThat(merge("", "ours\n", "theirs\n").isClean()).isFalse();
        }

        @Test
        void aFileEmptiedByOneSideAndUntouchedByTheOtherBecomesEmpty() {
            assertThat(content(merge("a\nb\n", "", "a\nb\n"))).isEmpty();
        }

        @Test
        void identicalInputsMergeToThemselves() {
            assertThat(content(merge("same\n", "same\n", "same\n"))).isEqualTo("same\n");
        }

        @Test
        void everyLineChangedByOneSideOnlyIsStillClean() {
            assertThat(content(merge("a\nb\nc\n", "x\ny\nz\n", "a\nb\nc\n"))).isEqualTo("x\ny\nz\n");
        }

        @Test
        void aFileTooLongToAlignIsNotAttempted() {
            String huge = "line\n".repeat(20_001);

            // Reported as not attempted rather than as a clean merge: the caller
            // has learned nothing, which is different from having learned there
            // is no conflict.
            assertThat(LineMerger.merge(huge, huge + "ours\n", huge + "theirs\n")).isEmpty();
        }

        @Test
        void rejectsNullArguments() {
            assertThatThrownBy(() -> LineMerger.merge(null, "a", "a"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> LineMerger.merge("a", null, "a"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> LineMerger.merge("a", "a", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("line ranges")
    class Ranges {

        @Test
        void aRangeMayBeEmptyButNeverBackwards() {
            assertThat(new LineRange(4, 4).isEmpty()).isTrue();
            assertThat(new LineRange(4, 7).length()).isEqualTo(3);
            assertThatThrownBy(() -> new LineRange(7, 4)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new LineRange(0, 1)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void aRegionNeedsAllThreeSides() {
            LineRange range = new LineRange(1, 2);

            assertThatThrownBy(() -> new ConflictRegion(null, range, range))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ConflictRegion(range, null, range))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ConflictRegion(range, range, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void aConflictedResultMustCarryAtLeastOneRegion() {
            assertThatThrownBy(() -> new LineMergeResult.Conflicted(List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new LineMergeResult.Clean(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
