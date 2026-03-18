package com.gitforge.vcs.repository;

import com.gitforge.vcs.diff.FileDiff;
import com.gitforge.vcs.diff.Hunk;
import com.gitforge.vcs.object.FileMode;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiffServiceTest {

    @TempDir
    Path storageRoot;

    private VcsRepository repository;
    private int sequence;

    @BeforeEach
    void setUp() {
        repository = new VcsRepositoryFactory(storageRoot).initialise(RepositoryId.of("demo"), "main");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private ObjectId commit(String message, FileChange... changes) {
        Signature author = Signature.of(
                "Ada", "ada@example.com", Instant.ofEpochSecond(1_700_000_000L + sequence++));
        return repository.commits().commit("main", List.of(changes), author, message);
    }

    private FileDiff only(List<FileDiff> diffs) {
        assertThat(diffs).hasSize(1);
        return diffs.getFirst();
    }

    @Nested
    @DisplayName("commit diffs")
    class CommitDiffs {

        @Test
        void anInitialCommitIsAllAdditions() {
            ObjectId first = commit("Initial",
                    FileChange.put("a.txt", bytes("one\ntwo\n")),
                    FileChange.put("b.txt", bytes("bee\n")));

            List<FileDiff> diffs = repository.diffs().diffCommit(first, null);

            assertThat(diffs).extracting(FileDiff::path).containsExactly("a.txt", "b.txt");
            assertThat(diffs).extracting(FileDiff::status)
                    .containsOnly(FileDiff.Status.ADDED);
            assertThat(diffs.getFirst().additions()).isEqualTo(2);
            assertThat(diffs.getFirst().deletions()).isZero();
        }

        @Test
        void aModificationCarriesHunks() {
            commit("Initial", FileChange.put("a.txt", bytes("one\ntwo\nthree\n")));
            ObjectId second = commit("Edit", FileChange.put("a.txt", bytes("one\nTWO\nthree\n")));

            FileDiff diff = only(repository.diffs().diffCommit(second, null));

            assertThat(diff.status()).isEqualTo(FileDiff.Status.MODIFIED);
            assertThat(diff.additions()).isEqualTo(1);
            assertThat(diff.deletions()).isEqualTo(1);
            assertThat(diff.hunks()).hasSize(1);

            Hunk hunk = diff.hunks().getFirst();
            assertThat(hunk.lines()).extracting(line -> line.content())
                    .containsExactly("one", "two", "TWO", "three");
        }

        @Test
        void aDeletionReportsRemovedLines() {
            commit("Initial", FileChange.put("a.txt", bytes("one\ntwo\n")),
                    FileChange.put("keep.txt", bytes("keep\n")));
            ObjectId second = commit("Remove", FileChange.delete("a.txt"));

            FileDiff diff = only(repository.diffs().diffCommit(second, null));

            assertThat(diff.status()).isEqualTo(FileDiff.Status.DELETED);
            assertThat(diff.deletions()).isEqualTo(2);
            assertThat(diff.newBlob()).isNull();
        }

        @Test
        void aMergeIsComparedWithItsFirstParent() {
            commit("Initial", FileChange.put("a.txt", bytes("base\n")), FileChange.put("b.txt", bytes("base\n")));
            repository.branches().createBranchFrom("feature", "main");

            commit("Main edit", FileChange.put("a.txt", bytes("main\n")));
            repository.commits().commit("feature", List.of(FileChange.put("b.txt", bytes("feature\n"))),
                    Signature.of("Ada", "ada@example.com", Instant.ofEpochSecond(1_700_009_000L)), "Feature edit");

            var outcome = (MergeOutcome.Merged) repository.merges().merge(
                    "main", "feature",
                    Signature.of("Ada", "ada@example.com", Instant.ofEpochSecond(1_700_009_500L)), "Merge");

            // Against the first parent, only their side's change appears; our own
            // is already present on the branch being merged into.
            List<FileDiff> diffs = repository.diffs().diffCommit(outcome.mergeCommit(), null);

            assertThat(diffs).extracting(FileDiff::path).containsExactly("b.txt");
        }

        @Test
        void aPathFilterNarrowsTheResult() {
            commit("Initial", FileChange.put("a.txt", bytes("a\n")), FileChange.put("b.txt", bytes("b\n")));
            ObjectId second = commit("Edit both",
                    FileChange.put("a.txt", bytes("A\n")),
                    FileChange.put("b.txt", bytes("B\n")));

            assertThat(repository.diffs().diffCommit(second, "a.txt"))
                    .extracting(FileDiff::path).containsExactly("a.txt");
        }
    }

    @Nested
    @DisplayName("content kinds")
    class ContentKinds {

        @Test
        void binaryFilesAreFlaggedAndNotLineDiffed() {
            byte[] binary = new byte[256];
            for (int i = 0; i < binary.length; i++) {
                binary[i] = (byte) i;
            }
            ObjectId first = commit("Add binary", FileChange.put("data.bin", binary));

            FileDiff diff = only(repository.diffs().diffCommit(first, null));

            // Line numbers are meaningless here, so no hunks are invented.
            assertThat(diff.binary()).isTrue();
            assertThat(diff.hunks()).isEmpty();
            assertThat(diff.tooLarge()).isFalse();

            // Size is the only measure of a change that cannot be shown as
            // lines, so it is reported even when nothing else can be.
            assertThat(diff.newSize()).isEqualTo(256);
            assertThat(diff.oldSize()).isZero();
        }

        @Test
        void sizesAreReportedForBothSidesOfAChange() {
            commit("Initial", FileChange.put("notes.txt", bytes("one\n")));
            ObjectId second = commit("Extend", FileChange.put("notes.txt", bytes("one\ntwo\n")));

            FileDiff diff = only(repository.diffs().diffCommit(second, null));

            assertThat(diff.oldSize()).isEqualTo(4);
            assertThat(diff.newSize()).isEqualTo(8);
        }

        @Test
        void aDeletedFileHasNoNewSize() {
            commit("Initial", FileChange.put("gone.txt", bytes("bye\n")));
            ObjectId second = commit("Remove", FileChange.delete("gone.txt"));

            FileDiff diff = only(repository.diffs().diffCommit(second, null));

            assertThat(diff.oldSize()).isEqualTo(4);
            assertThat(diff.newSize()).isZero();
        }

        @Test
        void aModeOnlyChangeIsReportedWithoutContentChanges() {
            commit("Initial", FileChange.put("run.sh", bytes("#!/bin/sh\n")));
            ObjectId second = commit("Make executable",
                    FileChange.put("run.sh", bytes("#!/bin/sh\n"), FileMode.EXECUTABLE_FILE));

            FileDiff diff = only(repository.diffs().diffCommit(second, null));

            assertThat(diff.oldMode()).isEqualTo(FileMode.REGULAR_FILE);
            assertThat(diff.newMode()).isEqualTo(FileMode.EXECUTABLE_FILE);
            assertThat(diff.additions()).isZero();
            assertThat(diff.deletions()).isZero();
            assertThat(diff.hunks()).isEmpty();
        }

        @Test
        void anEmptyFileAddedReportsNoLines() {
            ObjectId first = commit("Add empty", FileChange.put("empty.txt", new byte[0]));

            FileDiff diff = only(repository.diffs().diffCommit(first, null));

            assertThat(diff.status()).isEqualTo(FileDiff.Status.ADDED);
            assertThat(diff.additions()).isZero();
        }
    }

    @Nested
    @DisplayName("comparing revisions")
    class Comparing {

        @Test
        void comparesTwoBranches() {
            commit("Initial", FileChange.put("a.txt", bytes("base\n")));
            repository.branches().createBranchFrom("feature", "main");
            repository.commits().commit("feature",
                    List.of(FileChange.put("a.txt", bytes("changed\n")),
                            FileChange.put("new.txt", bytes("new\n"))),
                    Signature.of("Ada", "ada@example.com", Instant.ofEpochSecond(1_700_009_000L)),
                    "Feature work");

            ObjectId mainTree = repository.objects()
                    .readCommit(repository.branches().getBranch("main").orElseThrow()).tree();
            ObjectId featureTree = repository.objects()
                    .readCommit(repository.branches().getBranch("feature").orElseThrow()).tree();

            List<FileDiff> diffs = repository.diffs().diffTrees(mainTree, featureTree, null);

            assertThat(diffs).extracting(FileDiff::path).containsExactly("a.txt", "new.txt");
            assertThat(diffs.getFirst().status()).isEqualTo(FileDiff.Status.MODIFIED);
        }

        @Test
        void identicalTreesDifferInNothing() {
            ObjectId first = commit("Initial", FileChange.put("a.txt", bytes("a\n")));
            ObjectId tree = repository.objects().readCommit(first).tree();

            assertThat(repository.diffs().diffTrees(tree, tree, null)).isEmpty();
        }

        @Test
        void hunksAreOmittedBeyondTheFileLimit() {
            FileChange[] many = new FileChange[DiffService.MAX_FILES_WITH_HUNKS + 5];
            for (int i = 0; i < many.length; i++) {
                many[i] = FileChange.put("file" + i + ".txt", bytes("content " + i + "\n"));
            }
            ObjectId first = commit("Many files", many);

            List<FileDiff> diffs = repository.diffs().diffCommit(first, null);

            // Every file is still reported; only the detail is capped.
            assertThat(diffs).hasSize(many.length);
            assertThat(diffs.stream().filter(FileDiff::hasHunks))
                    .hasSize(DiffService.MAX_FILES_WITH_HUNKS);
            assertThat(diffs.stream().filter(FileDiff::tooLarge)).hasSize(5);
        }
    }
}
