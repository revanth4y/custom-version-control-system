package com.gitforge.vcs.diff;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which characters changed within a pair of lines.
 *
 * <p>Every assertion is expressed as the text the segments actually cover,
 * recovered from the line's own content. A test that asserted raw offsets would
 * pass just as happily on an off-by-one, and the offsets are the one thing a
 * client cannot check for itself.
 */
class InlineDifferTest {

    /** One hunk of a removed line followed by its added counterpart. */
    private static List<Hunk> pair(String before, String after) {
        return List.of(new Hunk(1, 1, 1, 1, List.of(
                DiffLine.removed(1, before),
                DiffLine.added(1, after))));
    }

    private static List<DiffLine> annotate(String before, String after) {
        return new InlineDiffer().annotate(pair(before, after)).getFirst().lines();
    }

    /** The substrings the segments point at, which is what the reader sees marked. */
    private static List<String> marked(DiffLine line) {
        return line.segments().stream()
                .map(segment -> line.content().substring(segment.start(), segment.end()))
                .toList();
    }

    private static DiffLine removedOf(List<DiffLine> lines) {
        return lines.stream().filter(l -> l.type() == DiffLine.Type.REMOVED).findFirst().orElseThrow();
    }

    private static DiffLine addedOf(List<DiffLine> lines) {
        return lines.stream().filter(l -> l.type() == DiffLine.Type.ADDED).findFirst().orElseThrow();
    }

    @Nested
    @DisplayName("what changed inside a line")
    class Changes {

        @Test
        void marksOnlyTheDifferingCharacters() {
            List<DiffLine> lines = annotate("timeout = 30", "timeout = 60");

            assertThat(marked(removedOf(lines))).containsExactly("3");
            assertThat(marked(addedOf(lines))).containsExactly("6");
        }

        @Test
        void marksAChangedTokenAsTheCharactersThatActuallyDiffer() {
            /* Character-level, not token-level, which is what was specified. The
               two words share an "n", so the run breaks around it rather than
               covering the whole word: "i" and "t" of "int" against "lo" and "g"
               of "long". Pinned deliberately - it is the visible consequence of
               comparing characters, and a future decision to bridge short
               unchanged gaps would change exactly this assertion. */
            List<DiffLine> lines = annotate("int count = 0;", "long count = 0;");

            assertThat(marked(removedOf(lines))).containsExactly("i", "t");
            assertThat(marked(addedOf(lines))).containsExactly("lo", "g");

            // Whichever way the runs fall, only the differing word is touched:
            // the unchanged remainder of the line is never marked.
            assertThat(removedOf(lines).segments())
                    .allSatisfy(segment -> assertThat(segment.end()).isLessThanOrEqualTo(3));
        }

        @Test
        void marksAWhollyDifferentTokenAsOneRun() {
            // No character in common, so the run is contiguous and covers the
            // whole token - the clearest case, and the one a reader benefits
            // from most.
            List<DiffLine> lines = annotate("mode = up;", "mode = 42;");

            assertThat(marked(removedOf(lines))).containsExactly("up");
            assertThat(marked(addedOf(lines))).containsExactly("42");
        }

        @Test
        void confinesRunsToTheChangedTokenEvenWhenTheySplit() {
            /* "fast" and "slow" share an "s", so the runs break around it. What
               matters, and what this pins, is that nothing outside the changed
               token is ever marked: the reader's eye is still taken to the right
               place, even when the marking within it is not one block. */
            String before = "mode = fast;";
            List<DiffLine> lines = annotate(before, "mode = slow;");

            assertThat(marked(removedOf(lines))).containsExactly("fa", "t");
            assertThat(marked(addedOf(lines))).containsExactly("low");

            int tokenStart = before.indexOf("fast");
            assertThat(removedOf(lines).segments()).allSatisfy(segment -> {
                assertThat(segment.start()).isGreaterThanOrEqualTo(tokenStart);
                assertThat(segment.end()).isLessThanOrEqualTo(tokenStart + "fast".length());
            });
        }

        @Test
        void marksSeveralSeparateChangesSeparately() {
            // The reason a plain prefix/suffix trim is not enough on its own: it
            // would produce one span swallowing the unchanged middle.
            List<DiffLine> lines = annotate("alpha X beta Y gamma", "alpha P beta Q gamma");

            assertThat(marked(removedOf(lines))).containsExactly("X", "Y");
            assertThat(marked(addedOf(lines))).containsExactly("P", "Q");
        }

        @Test
        void marksAPureInsertionOnTheAddedSideOnly() {
            List<DiffLine> lines = annotate("value = 1", "value = 1 + 1");

            assertThat(removedOf(lines).segments()).isEmpty();
            assertThat(marked(addedOf(lines))).containsExactly(" + 1");
        }

        @Test
        void marksAPureDeletionOnTheRemovedSideOnly() {
            List<DiffLine> lines = annotate("value = 1 + 1", "value = 1");

            assertThat(marked(removedOf(lines))).containsExactly(" + 1");
            assertThat(addedOf(lines).segments()).isEmpty();
        }

        @Test
        void marksALeadingWhitespaceChange() {
            List<DiffLine> lines = annotate("    indented", "\tindented");

            assertThat(marked(removedOf(lines))).containsExactly("    ");
            assertThat(marked(addedOf(lines))).containsExactly("\t");
        }

        @Test
        void marksATrailingWhitespaceChange() {
            List<DiffLine> lines = annotate("trailing", "trailing   ");

            assertThat(removedOf(lines).segments()).isEmpty();
            assertThat(marked(addedOf(lines))).containsExactly("   ");
        }

        @Test
        void marksAChangeAtTheVeryStart() {
            List<DiffLine> lines = annotate("aaa", "baa");

            assertThat(marked(removedOf(lines))).containsExactly("a");
            assertThat(removedOf(lines).segments().getFirst().start()).isZero();
        }

        @Test
        void marksAChangeAtTheVeryEnd() {
            List<DiffLine> lines = annotate("aaa", "aab");
            DiffLine removed = removedOf(lines);

            assertThat(marked(removed)).containsExactly("a");
            assertThat(removed.segments().getFirst().end()).isEqualTo(removed.content().length());
        }
    }

    @Nested
    @DisplayName("unicode")
    class Unicode {

        @Test
        void marksTheChangedCharacterInAccentedText() {
            List<DiffLine> lines = annotate("café au lait", "café au lá it");

            assertThat(removedOf(lines).segments()).isNotEmpty();
            assertThat(addedOf(lines).segments()).isNotEmpty();
        }

        @Test
        void offsetsIndexTheSameUnitsTheClientWillUse() {
            /* Offsets are UTF-16 code units, which is what String.substring uses
               here and what a JavaScript string uses in the browser. An emoji
               outside the basic plane occupies two of them, so this pins that
               the arithmetic survives one. */
            String before = "status: 😀 ok";
            String after = "status: 😀 no";
            List<DiffLine> lines = annotate(before, after);

            DiffLine added = addedOf(lines);
            assertThat(String.join("", marked(added))).isEqualTo("n");
            assertThat(added.segments()).allSatisfy(segment ->
                    assertThat(segment.end()).isLessThanOrEqualTo(added.content().length()));
        }

        @Test
        void neverCutsASurrogatePairInHalf() {
            /* The two emoji share a high surrogate, so comparing code units finds
               them differing only in the second half. A run starting there would
               split the character, and both the marked and unmarked side would
               render as a replacement glyph instead of the emoji. */
            List<DiffLine> lines = annotate("x 😀 y", "x 😞 y");

            for (DiffLine line : lines) {
                for (Segment segment : line.segments()) {
                    String covered = line.content().substring(segment.start(), segment.end());
                    assertThat(Character.isLowSurrogate(covered.charAt(0))).isFalse();
                    assertThat(Character.isHighSurrogate(covered.charAt(covered.length() - 1))).isFalse();
                }
            }
        }

        @Test
        void marksAChangedEmojiWhole() {
            List<DiffLine> lines = annotate("x 😀 y", "x 😞 y");

            assertThat(marked(removedOf(lines))).containsExactly("😀");
            assertThat(marked(addedOf(lines))).containsExactly("😞");
        }

        @Test
        void leavesTheTextEitherSideOfAnEmojiIntact() {
            List<DiffLine> lines = annotate("x 😀 y", "x 😞 y");
            DiffLine removed = removedOf(lines);
            Segment segment = removed.segments().getFirst();

            // The unchanged remainder must also be whole: a boundary that split
            // the pair would leave a stray surrogate on this side too.
            String before = removed.content().substring(0, segment.start());
            assertThat(before).isEqualTo("x ");
            assertThat(removed.content().substring(segment.end())).isEqualTo(" y");
        }
    }

    @Nested
    @DisplayName("segment invariants")
    class Invariants {

        private static final String BEFORE = "one two three four five six";
        private static final String AFTER = "one 2 three 4 five 6";

        @Test
        void everySegmentIsWithinItsLine() {
            for (DiffLine line : annotate(BEFORE, AFTER)) {
                for (Segment segment : line.segments()) {
                    assertThat(segment.start()).isGreaterThanOrEqualTo(0);
                    assertThat(segment.end()).isGreaterThan(segment.start());
                    assertThat(segment.end()).isLessThanOrEqualTo(line.content().length());
                }
            }
        }

        @Test
        void segmentsAreStrictlyAscendingAndNonOverlapping() {
            for (DiffLine line : annotate(BEFORE, AFTER)) {
                List<Segment> segments = line.segments();
                for (int i = 1; i < segments.size(); i++) {
                    assertThat(segments.get(i).start()).isGreaterThanOrEqualTo(segments.get(i - 1).end());
                }
            }
        }

        @Test
        void noSegmentIsEmpty() {
            assertThatThrownBy(() -> new Segment(4, 4)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Segment(5, 4)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Segment(-1, 2)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void adjacentRunsAreMergedRatherThanEmittedSeparately() {
            List<DiffLine> lines = annotate("abcd", "axyd");

            assertThat(marked(removedOf(lines))).containsExactly("bc");
            assertThat(marked(addedOf(lines))).containsExactly("xy");
        }
    }

    @Nested
    @DisplayName("lines that are not annotated")
    class NotAnnotated {

        @Test
        void contextLinesNeverReceiveSegments() {
            List<Hunk> hunks = List.of(new Hunk(1, 3, 1, 3, List.of(
                    DiffLine.context(1, 1, "unchanged before"),
                    DiffLine.removed(2, "value = 1"),
                    DiffLine.added(2, "value = 2"),
                    DiffLine.context(3, 3, "unchanged after"))));

            List<DiffLine> lines = new InlineDiffer().annotate(hunks).getFirst().lines();

            assertThat(lines.stream().filter(l -> l.type() == DiffLine.Type.CONTEXT))
                    .allSatisfy(line -> assertThat(line.segments()).isEmpty());
        }

        @Test
        void identicalLinesProduceNoSegments() {
            // Unreachable through LineDiffer, which would not call them changed;
            // asserted because the comparison below assumes otherwise.
            List<DiffLine> lines = annotate("same", "same");

            assertThat(removedOf(lines).segments()).isEmpty();
            assertThat(addedOf(lines).segments()).isEmpty();
        }

        @Test
        void anAdditionWithNoRemovedCounterpartIsNotAnnotated() {
            List<Hunk> hunks = List.of(new Hunk(1, 0, 1, 2, List.of(
                    DiffLine.added(1, "brand new"),
                    DiffLine.added(2, "also new"))));

            assertThat(new InlineDiffer().annotate(hunks).getFirst().lines())
                    .allSatisfy(line -> assertThat(line.segments()).isEmpty());
        }

        @Test
        void aDeletionWithNoAddedCounterpartIsNotAnnotated() {
            List<Hunk> hunks = List.of(new Hunk(1, 2, 1, 0, List.of(
                    DiffLine.removed(1, "gone"),
                    DiffLine.removed(2, "also gone"))));

            assertThat(new InlineDiffer().annotate(hunks).getFirst().lines())
                    .allSatisfy(line -> assertThat(line.segments()).isEmpty());
        }

        @Test
        void anUnequalRunPairsOnlyAsFarAsBothSidesReach() {
            List<Hunk> hunks = List.of(new Hunk(1, 3, 1, 1, List.of(
                    DiffLine.removed(1, "value = 1"),
                    DiffLine.removed(2, "second removed"),
                    DiffLine.removed(3, "third removed"),
                    DiffLine.added(1, "value = 2"))));

            List<DiffLine> lines = new InlineDiffer().annotate(hunks).getFirst().lines();

            assertThat(lines.get(0).segments()).isNotEmpty();
            assertThat(lines.get(1).segments()).isEmpty();
            assertThat(lines.get(2).segments()).isEmpty();
            assertThat(lines.get(3).segments()).isNotEmpty();
        }

        @Test
        void aLineLongerThanTheCeilingIsSkippedWithoutFailing() {
            String before = "x".repeat(InlineDiffer.MAX_LINE_CHARS + 1);
            String after = before.substring(0, before.length() - 1) + "y";

            List<DiffLine> lines = annotate(before, after);

            assertThat(removedOf(lines).segments()).isEmpty();
            assertThat(addedOf(lines).segments()).isEmpty();
            // The lines themselves survive untouched; only the annotation is absent.
            assertThat(removedOf(lines).content()).isEqualTo(before);
            assertThat(addedOf(lines).content()).isEqualTo(after);
        }

        @Test
        void aLineRewrittenBeyondTheCoreCeilingIsSkipped() {
            String before = "a".repeat(InlineDiffer.MAX_CORE_CHARS + 10);
            String after = "b".repeat(InlineDiffer.MAX_CORE_CHARS + 10);

            List<DiffLine> lines = annotate(before, after);

            assertThat(removedOf(lines).segments()).isEmpty();
            assertThat(addedOf(lines).segments()).isEmpty();
        }

        @Test
        void aLongLineWithASmallEditIsStillAnnotated() {
            // The ceiling is on the differing core, not the line, so a long line
            // with a one-character edit is exactly the case that still works.
            String before = "y".repeat(500) + "0" + "z".repeat(400);
            String after = "y".repeat(500) + "1" + "z".repeat(400);

            List<DiffLine> lines = annotate(before, after);

            assertThat(marked(removedOf(lines))).containsExactly("0");
            assertThat(marked(addedOf(lines))).containsExactly("1");
        }
    }

    @Nested
    @DisplayName("the response budget")
    class Budget {

        private static List<Hunk> pairs(int count) {
            List<DiffLine> lines = new java.util.ArrayList<>();
            for (int i = 0; i < count; i++) {
                lines.add(DiffLine.removed(i + 1, "value = 1"));
                lines.add(DiffLine.added(i + 1, "value = 2"));
            }
            return List.of(new Hunk(1, count, 1, count, lines));
        }

        @Test
        void annotatesUpToTheBudgetAndThenStops() {
            List<DiffLine> lines = new InlineDiffer(2).annotate(pairs(4)).getFirst().lines();

            // Runs are zipped in order, so the first two removals pair with the
            // first two additions; the budget stops the rest.
            long annotated = lines.stream().filter(line -> !line.segments().isEmpty()).count();
            assertThat(annotated).isEqualTo(4);
        }

        @Test
        void aZeroBudgetAnnotatesNothingAndStillReturnsEveryLine() {
            List<Hunk> result = new InlineDiffer(0).annotate(pairs(3));

            assertThat(result.getFirst().lines()).hasSize(6);
            assertThat(result.getFirst().lines()).allSatisfy(line -> assertThat(line.segments()).isEmpty());
        }

        @Test
        void theBudgetSpansEveryCallOnOneDiffer() {
            InlineDiffer differ = new InlineDiffer(1);
            differ.annotate(pairs(1));

            // A second file in the same response finds the budget already spent.
            assertThat(differ.annotate(pairs(1)).getFirst().lines())
                    .allSatisfy(line -> assertThat(line.segments()).isEmpty());
        }

        @Test
        void anOversizedPairDoesNotSpendTheBudget() {
            String huge = "x".repeat(InlineDiffer.MAX_LINE_CHARS + 1);
            List<Hunk> hunks = List.of(new Hunk(1, 2, 1, 2, List.of(
                    DiffLine.removed(1, huge),
                    DiffLine.removed(2, "value = 1"),
                    DiffLine.added(1, huge + "!"),
                    DiffLine.added(2, "value = 2"))));

            List<DiffLine> lines = new InlineDiffer(1).annotate(hunks).getFirst().lines();

            // The skipped pair cost nothing, so the affordable one is still done.
            assertThat(lines.get(1).segments()).isNotEmpty();
            assertThat(lines.get(3).segments()).isNotEmpty();
        }

        @Test
        void refusesANegativeBudget() {
            assertThatThrownBy(() -> new InlineDiffer(-1)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("what it leaves alone")
    class LeavesAlone {

        @Test
        void preservesLineTypesNumbersAndContent() {
            List<Hunk> hunks = List.of(new Hunk(7, 2, 9, 2, List.of(
                    DiffLine.context(7, 9, "context"),
                    DiffLine.removed(8, "value = 1"),
                    DiffLine.added(10, "value = 2"))));

            List<DiffLine> lines = new InlineDiffer().annotate(hunks).getFirst().lines();

            assertThat(lines.get(0).type()).isEqualTo(DiffLine.Type.CONTEXT);
            assertThat(lines.get(1).oldNumber()).isEqualTo(8);
            assertThat(lines.get(1).newNumber()).isNull();
            assertThat(lines.get(2).newNumber()).isEqualTo(10);
            assertThat(lines.get(2).content()).isEqualTo("value = 2");
        }

        @Test
        void preservesHunkBoundaries() {
            List<Hunk> hunks = List.of(new Hunk(7, 2, 9, 2, List.of(
                    DiffLine.removed(7, "a"),
                    DiffLine.added(9, "b"))));

            Hunk result = new InlineDiffer().annotate(hunks).getFirst();

            assertThat(result.oldStart()).isEqualTo(7);
            assertThat(result.oldCount()).isEqualTo(2);
            assertThat(result.newStart()).isEqualTo(9);
            assertThat(result.newCount()).isEqualTo(2);
        }

        @Test
        void returnsEmptyForNoHunks() {
            assertThat(new InlineDiffer().annotate(List.of())).isEmpty();
        }
    }
}
