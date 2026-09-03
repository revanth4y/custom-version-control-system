package com.gitforge.vcs.diff;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class LineDifferTest {

    /** Renders hunks the way a unified diff would, for readable assertions. */
    private static String unified(String oldText, String newText) {
        return LineDiffer.diff(oldText, newText).orElseThrow().stream()
                .flatMap(hunk -> hunk.lines().stream())
                .map(line -> switch (line.type()) {
                    case ADDED -> "+" + line.content();
                    case REMOVED -> "-" + line.content();
                    case CONTEXT -> " " + line.content();
                })
                .collect(Collectors.joining("\n"));
    }

    private static List<Hunk> hunks(String oldText, String newText) {
        return LineDiffer.diff(oldText, newText).orElseThrow();
    }

    @Nested
    @DisplayName("basic edits")
    class BasicEdits {

        @Test
        void identicalTextProducesNoHunks() {
            assertThat(hunks("a\nb\nc\n", "a\nb\nc\n")).isEmpty();
        }

        @Test
        void bothEmptyProducesNoHunks() {
            assertThat(hunks("", "")).isEmpty();
        }

        @Test
        void aChangedLineIsRemovedAndAdded() {
            assertThat(unified("a\nb\nc\n", "a\nB\nc\n")).isEqualTo("""
                     a
                    -b
                    +B
                     c""");
        }

        @Test
        void anInsertedLine() {
            assertThat(unified("a\nc\n", "a\nb\nc\n")).isEqualTo("""
                     a
                    +b
                     c""");
        }

        @Test
        void aDeletedLine() {
            assertThat(unified("a\nb\nc\n", "a\nc\n")).isEqualTo("""
                     a
                    -b
                     c""");
        }

        @Test
        void appendingToTheEnd() {
            assertThat(unified("a\n", "a\nb\n")).isEqualTo("""
                     a
                    +b""");
        }

        @Test
        void prependingToTheStart() {
            assertThat(unified("b\n", "a\nb\n")).isEqualTo("""
                    +a
                     b""");
        }

        @Test
        void creatingContentFromNothing() {
            assertThat(unified("", "a\nb\n")).isEqualTo("""
                    +a
                    +b""");
        }

        @Test
        void deletingAllContent() {
            assertThat(unified("a\nb\n", "")).isEqualTo("""
                    -a
                    -b""");
        }

        @Test
        void aCompleteRewrite() {
            assertThat(unified("a\nb\n", "x\ny\n")).isEqualTo("""
                    -a
                    -b
                    +x
                    +y""");
        }
    }

    @Nested
    @DisplayName("line numbering")
    class Numbering {

        @Test
        void addedLinesCarryOnlyANewNumber() {
            List<DiffLine> lines = hunks("a\nc\n", "a\nb\nc\n").getFirst().lines();
            DiffLine added = lines.stream()
                    .filter(line -> line.type() == DiffLine.Type.ADDED).findFirst().orElseThrow();

            assertThat(added.oldNumber()).isNull();
            assertThat(added.newNumber()).isEqualTo(2);
        }

        @Test
        void removedLinesCarryOnlyAnOldNumber() {
            List<DiffLine> lines = hunks("a\nb\nc\n", "a\nc\n").getFirst().lines();
            DiffLine removed = lines.stream()
                    .filter(line -> line.type() == DiffLine.Type.REMOVED).findFirst().orElseThrow();

            assertThat(removed.oldNumber()).isEqualTo(2);
            assertThat(removed.newNumber()).isNull();
        }

        @Test
        void contextLinesCarryBothNumbers() {
            DiffLine first = hunks("a\nb\n", "a\nB\n").getFirst().lines().getFirst();

            assertThat(first.type()).isEqualTo(DiffLine.Type.CONTEXT);
            assertThat(first.oldNumber()).isEqualTo(1);
            assertThat(first.newNumber()).isEqualTo(1);
        }

        @Test
        void numbersSurviveTrimmingOfALongIdenticalPrefix() {
            // The prefix is trimmed for speed, so numbering must be restored
            // relative to the untrimmed file rather than the diffed core.
            String prefix = "same\n".repeat(50);
            List<DiffLine> lines = hunks(prefix + "old\n", prefix + "new\n").getFirst().lines();

            DiffLine removed = lines.stream()
                    .filter(line -> line.type() == DiffLine.Type.REMOVED).findFirst().orElseThrow();
            assertThat(removed.oldNumber()).isEqualTo(51);
        }
    }

    @Nested
    @DisplayName("hunks and context")
    class Hunks {

        @Test
        void aChangeCarriesThreeLinesOfContextEitherSide() {
            String before = "1\n2\n3\n4\n5\n6\n7\n8\n9\n";
            String after = "1\n2\n3\n4\nFIVE\n6\n7\n8\n9\n";

            List<DiffLine> lines = hunks(before, after).getFirst().lines();

            assertThat(lines).hasSize(8); // 3 context + removed + added + 3 context
            assertThat(lines.getFirst().content()).isEqualTo("2");
            assertThat(lines.getLast().content()).isEqualTo("8");
        }

        @Test
        void distantChangesBecomeSeparateHunks() {
            String before = ("x\n".repeat(1)) + "pad\n".repeat(20) + "y\n";
            String after = ("X\n".repeat(1)) + "pad\n".repeat(20) + "Y\n";

            assertThat(hunks(before, after)).hasSize(2);
        }

        @Test
        void nearbyChangesMergeIntoOneHunk() {
            String before = "1\n2\n3\n4\n5\n";
            String after = "ONE\n2\n3\n4\nFIVE\n";

            // Only three lines apart, so their context regions overlap.
            assertThat(hunks(before, after)).hasSize(1);
        }

        @Test
        void hunkHeaderDescribesBothSides() {
            Hunk hunk = hunks("a\nb\nc\n", "a\nB\nc\n").getFirst();

            assertThat(hunk.oldStart()).isEqualTo(1);
            assertThat(hunk.newStart()).isEqualTo(1);
            assertThat(hunk.oldCount()).isEqualTo(3);
            assertThat(hunk.newCount()).isEqualTo(3);
            assertThat(hunk.header()).isEqualTo("@@ -1,3 +1,3 @@");
        }

        @Test
        void contextIsRecoveredFromTheTrimmedPrefix() {
            String prefix = "same\n".repeat(10);
            List<DiffLine> lines = hunks(prefix + "old\n", prefix + "new\n").getFirst().lines();

            // Trimming must not cost the change its leading context.
            assertThat(lines.stream().filter(l -> l.type() == DiffLine.Type.CONTEXT)).hasSize(3);
            assertThat(lines.getFirst().oldNumber()).isEqualTo(8);
        }
    }

    @Nested
    @DisplayName("content handling")
    class Content {

        @Test
        void aFileWithoutATrailingNewlineHasNoPhantomLastLine() {
            // "a\nb" is two lines; without this the file would differ from itself.
            assertThat(hunks("a\nb", "a\nb")).isEmpty();
        }

        @Test
        void trailingNewlineDoesNotCreateAnEmptyLine() {
            assertThat(com.gitforge.vcs.object.TextContent.lines("a\n")).containsExactly("a");
            assertThat(com.gitforge.vcs.object.TextContent.lines("a\nb\n")).containsExactly("a", "b");
            assertThat(com.gitforge.vcs.object.TextContent.lines("")).isEmpty();
        }

        @Test
        void blankLinesAreRealContent() {
            assertThat(unified("a\n\nb\n", "a\nb\n")).isEqualTo("""
                     a
                    -
                     b""");
        }

        @Test
        void unicodeContentDiffsCorrectly() {
            assertThat(unified("héllo\n", "wörld\n")).isEqualTo("""
                    -héllo
                    +wörld""");
        }

        @Test
        void indentationChangesAreDetected() {
            assertThat(unified("a\n", "    a\n")).isEqualTo("""
                    -a
                    +    a""");
        }
    }

    @Nested
    @DisplayName("bounds")
    class Bounds {

        @Test
        void refusesFilesWithTooManyLines() {
            String huge = "line\n".repeat(LineDiffer.MAX_LINES + 1);

            assertThat(LineDiffer.diff(huge, "a\n")).isEmpty();
        }

        @Test
        void refusesTextsThatDifferTooMuch() {
            // Every line differs, so the edit distance exceeds the cap.
            StringBuilder before = new StringBuilder();
            StringBuilder after = new StringBuilder();
            for (int i = 0; i < LineDiffer.MAX_EDIT_DISTANCE; i++) {
                before.append("old").append(i).append('\n');
                after.append("new").append(i).append('\n');
            }

            assertThat(LineDiffer.diff(before.toString(), after.toString())).isEmpty();
        }

        @Test
        void handlesALargeFileWithASmallChangeQuickly() {
            // The point of trimming: size alone must not make a diff expensive.
            String body = java.util.stream.IntStream.range(0, 15_000)
                    .mapToObj(i -> "line " + i)
                    .collect(Collectors.joining("\n", "", "\n"));
            String edited = body.replace("line 7000", "line 7000 edited");

            long start = System.nanoTime();
            List<Hunk> result = hunks(body, edited);
            long millis = (System.nanoTime() - start) / 1_000_000;

            assertThat(result).hasSize(1);
            assertThat(millis).isLessThan(1_000);
        }
    }
}
