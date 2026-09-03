package com.gitforge.vcs.merge;

import com.gitforge.vcs.CountingObjectStore;
import com.gitforge.vcs.diff.TreeChange;
import com.gitforge.vcs.object.FileMode;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.storage.FileSystemObjectStore;
import com.gitforge.vcs.tree.TreeBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MergeConflictTest {

    @TempDir
    Path tempDir;

    private CountingObjectStore store;
    private ThreeWayMerger merger;

    @BeforeEach
    void setUp() {
        store = new CountingObjectStore(new FileSystemObjectStore(tempDir));
        merger = new ThreeWayMerger(store);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private ObjectId tree(Object... pathsAndContents) {
        TreeBuilder builder = new TreeBuilder(store);
        for (int i = 0; i < pathsAndContents.length; i += 2) {
            builder.addFile((String) pathsAndContents[i], bytes((String) pathsAndContents[i + 1]));
        }
        return builder.build();
    }

    private MergeResult.Conflicted conflicted(MergeResult result) {
        assertThat(result).isInstanceOf(MergeResult.Conflicted.class);
        return (MergeResult.Conflicted) result;
    }

    private MergeConflict onlyConflict(MergeResult result) {
        List<MergeConflict> conflicts = conflicted(result).conflicts();
        assertThat(conflicts).hasSize(1);
        return conflicts.getFirst();
    }

    @Nested
    @DisplayName("conflict kinds")
    class Kinds {

        @Test
        void bothSidesEditingOneFileDifferentlyIsAContentConflict() {
            ObjectId base = tree("a.txt", "base\n");
            ObjectId ours = tree("a.txt", "ours\n");
            ObjectId theirs = tree("a.txt", "theirs\n");

            MergeConflict conflict = onlyConflict(merger.merge(base, ours, theirs));

            assertThat(conflict.kind()).isEqualTo(ConflictKind.CONTENT);
            assertThat(conflict.path()).isEqualTo("a.txt");
            assertThat(conflict.base()).isPresent();
            assertThat(conflict.ours()).isPresent();
            assertThat(conflict.theirs()).isPresent();
        }

        @Test
        void bothSidesCreatingOnePathDifferentlyIsAnAddAddConflict() {
            ObjectId base = tree("other.txt", "other\n");
            ObjectId ours = tree("other.txt", "other\n", "new.txt", "ours\n");
            ObjectId theirs = tree("other.txt", "other\n", "new.txt", "theirs\n");

            MergeConflict conflict = onlyConflict(merger.merge(base, ours, theirs));

            assertThat(conflict.kind()).isEqualTo(ConflictKind.ADD_ADD);
            // Nothing was there before, which is what distinguishes this from CONTENT.
            assertThat(conflict.base()).isEmpty();
        }

        @Test
        void oursModifiedAndTheirsDeletedIsAModifyDeleteConflict() {
            ObjectId base = tree("a.txt", "base\n", "keep.txt", "keep\n");
            ObjectId ours = tree("a.txt", "ours edited\n", "keep.txt", "keep\n");
            ObjectId theirs = tree("keep.txt", "keep\n");

            MergeConflict conflict = onlyConflict(merger.merge(base, ours, theirs));

            assertThat(conflict.kind()).isEqualTo(ConflictKind.MODIFY_DELETE);
            // The direction is readable straight off the data.
            assertThat(conflict.ours()).isPresent();
            assertThat(conflict.theirs()).isEmpty();
        }

        @Test
        void oursDeletedAndTheirsModifiedIsAlsoAModifyDeleteConflict() {
            ObjectId base = tree("a.txt", "base\n", "keep.txt", "keep\n");
            ObjectId ours = tree("keep.txt", "keep\n");
            ObjectId theirs = tree("a.txt", "theirs edited\n", "keep.txt", "keep\n");

            MergeConflict conflict = onlyConflict(merger.merge(base, ours, theirs));

            assertThat(conflict.kind()).isEqualTo(ConflictKind.MODIFY_DELETE);
            assertThat(conflict.ours()).isEmpty();
            assertThat(conflict.theirs()).isPresent();
        }

        @Test
        void sameContentChangeWithDifferentModesIsAModeConflict() {
            // Both sides rewrote the file to exactly the same bytes, so there is
            // nothing to disagree about in the content — but only one side also
            // made it executable. That leaves the mode as the sole disagreement.
            ObjectId base = new TreeBuilder(store)
                    .addFile("run.sh", bytes("old\n"), FileMode.REGULAR_FILE).build();
            ObjectId ours = new TreeBuilder(store)
                    .addFile("run.sh", bytes("new\n"), FileMode.EXECUTABLE_FILE).build();
            ObjectId theirs = new TreeBuilder(store)
                    .addFile("run.sh", bytes("new\n"), FileMode.REGULAR_FILE).build();

            MergeConflict conflict = onlyConflict(merger.merge(base, ours, theirs));

            assertThat(conflict.kind()).isEqualTo(ConflictKind.MODE);
            assertThat(conflict.ours().orElseThrow().mode()).isEqualTo(FileMode.EXECUTABLE_FILE);
            assertThat(conflict.theirs().orElseThrow().mode()).isEqualTo(FileMode.REGULAR_FILE);
            // Identical content is exactly what makes this a mode conflict.
            assertThat(conflict.ours().orElseThrow().id())
                    .isEqualTo(conflict.theirs().orElseThrow().id());
        }

        @Test
        void aModeChangeOnOnlyOneSideMergesCleanly() {
            byte[] content = bytes("#!/bin/sh\n");
            ObjectId base = new TreeBuilder(store)
                    .addFile("run.sh", content, FileMode.REGULAR_FILE)
                    .addFile("keep.txt", bytes("keep\n")).build();
            ObjectId ours = new TreeBuilder(store)
                    .addFile("run.sh", content, FileMode.EXECUTABLE_FILE)
                    .addFile("keep.txt", bytes("keep\n")).build();
            ObjectId theirs = new TreeBuilder(store)
                    .addFile("run.sh", content, FileMode.REGULAR_FILE)
                    .addFile("keep.txt", bytes("changed\n")).build();

            // theirs never touched run.sh, so ours' mode change stands.
            assertThat(merger.merge(base, ours, theirs).isClean()).isTrue();
        }

        @Test
        void aFileAgainstADirectoryIsATypeConflict() {
            ObjectId base = tree("src", "a file\n");
            ObjectId ours = tree("src", "ours edited the file\n");
            ObjectId theirs = tree("src/App.java", "theirs made it a directory\n");

            MergeConflict conflict = onlyConflict(merger.merge(base, ours, theirs));

            assertThat(conflict.kind()).isEqualTo(ConflictKind.TYPE);
            assertThat(conflict.path()).isEqualTo("src");
            assertThat(conflict.ours()).get().extracting(MergeConflict.Side::isDirectory).isEqualTo(false);
            assertThat(conflict.theirs()).get().extracting(MergeConflict.Side::isDirectory).isEqualTo(true);
        }

        @Test
        void aDirectoryAgainstAFileIsAlsoATypeConflict() {
            ObjectId base = tree("src", "a file\n");
            ObjectId ours = tree("src/App.java", "ours made it a directory\n");
            ObjectId theirs = tree("src", "theirs edited the file\n");

            MergeConflict conflict = onlyConflict(merger.merge(base, ours, theirs));

            assertThat(conflict.kind()).isEqualTo(ConflictKind.TYPE);
            assertThat(conflict.ours()).get().extracting(MergeConflict.Side::isDirectory).isEqualTo(true);
            assertThat(conflict.theirs()).get().extracting(MergeConflict.Side::isDirectory).isEqualTo(false);
        }

        @Test
        void aConflictingDirectoryIsReportedAsOnePathNotItsFiles() {
            ObjectId base = tree("src", "a file\n");
            ObjectId ours = tree("src", "ours edited\n");
            ObjectId theirs = tree("src/App.java", "app\n", "src/Util.java", "util\n");

            MergeConflict conflict = onlyConflict(merger.merge(base, ours, theirs));

            // The disagreement is about what "src" is, not about its contents.
            assertThat(conflict.path()).isEqualTo("src");
        }
    }

    @Nested
    @DisplayName("conflicted results")
    class Results {

        @Test
        void aConflictedMergeProducesNoTree() {
            ObjectId base = tree("a.txt", "base\n");
            ObjectId ours = tree("a.txt", "ours\n");
            ObjectId theirs = tree("a.txt", "theirs\n");

            MergeResult result = merger.merge(base, ours, theirs);

            assertThat(result.isClean()).isFalse();
            assertThat(result).isNotInstanceOf(MergeResult.Clean.class);
        }

        @Test
        void aConflictedMergeWritesNothingToTheStore() {
            ObjectId base = tree("a.txt", "base\n", "b.txt", "b\n");
            ObjectId ours = tree("a.txt", "ours\n", "b.txt", "ours b\n");
            ObjectId theirs = tree("a.txt", "theirs\n", "b.txt", "theirs b\n");
            store.resetCounts();

            merger.merge(base, ours, theirs);

            // Trees built during the walk describe a state that was never
            // resolved, so none of them are persisted.
            assertThat(store.writeCount()).isZero();
        }

        @Test
        void everyConflictIsFoundInOnePassNotJustTheFirst() {
            ObjectId base = tree("a.txt", "base\n", "b.txt", "base\n", "c.txt", "base\n");
            ObjectId ours = tree("a.txt", "ours\n", "b.txt", "ours\n", "c.txt", "ours\n");
            ObjectId theirs = tree("a.txt", "theirs\n", "b.txt", "theirs\n", "c.txt", "theirs\n");

            assertThat(conflicted(merger.merge(base, ours, theirs)).conflicts())
                    .extracting(MergeConflict::path)
                    .containsExactly("a.txt", "b.txt", "c.txt");
        }

        @Test
        void conflictsAreSortedByPath() {
            ObjectId base = tree("z.txt", "base\n", "a.txt", "base\n", "m/deep.txt", "base\n");
            ObjectId ours = tree("z.txt", "ours\n", "a.txt", "ours\n", "m/deep.txt", "ours\n");
            ObjectId theirs = tree("z.txt", "theirs\n", "a.txt", "theirs\n", "m/deep.txt", "theirs\n");

            assertThat(conflicted(merger.merge(base, ours, theirs)).conflicts())
                    .extracting(MergeConflict::path)
                    .containsExactly("a.txt", "m/deep.txt", "z.txt")
                    .isSorted();
        }

        @Test
        void conflictsAreFoundInNestedDirectories() {
            ObjectId base = tree("src/deep/a.txt", "base\n");
            ObjectId ours = tree("src/deep/a.txt", "ours\n");
            ObjectId theirs = tree("src/deep/a.txt", "theirs\n");

            assertThat(onlyConflict(merger.merge(base, ours, theirs)).path())
                    .isEqualTo("src/deep/a.txt");
        }

        @Test
        void cleanlyMergedChangesAreReportedAlongsideConflicts() {
            ObjectId base = tree("conflicted.txt", "base\n", "theirs-only.txt", "base\n");
            ObjectId ours = tree("conflicted.txt", "ours\n", "theirs-only.txt", "base\n");
            ObjectId theirs = tree("conflicted.txt", "theirs\n", "theirs-only.txt", "theirs edited\n");

            MergeResult.Conflicted result = conflicted(merger.merge(base, ours, theirs));

            assertThat(result.conflicts()).extracting(MergeConflict::path).containsExactly("conflicted.txt");
            // What the merge did achieve, expressed as changes to ours.
            assertThat(result.cleanlyMerged()).extracting(TreeChange::path)
                    .containsExactly("theirs-only.txt");
        }

        @Test
        void cleanlyMergedReportsAdditionsFromTheirSide() {
            ObjectId base = tree("conflicted.txt", "base\n");
            ObjectId ours = tree("conflicted.txt", "ours\n");
            ObjectId theirs = tree("conflicted.txt", "theirs\n", "added/by-them.txt", "new\n");

            MergeResult.Conflicted result = conflicted(merger.merge(base, ours, theirs));

            assertThat(result.cleanlyMerged())
                    .singleElement()
                    .isInstanceOf(TreeChange.Added.class)
                    .extracting(TreeChange::path).isEqualTo("added/by-them.txt");
        }

        @Test
        void mixedConflictKindsAreAllReported() {
            ObjectId base = tree("content.txt", "base\n", "deleted.txt", "base\n");
            ObjectId ours = tree("content.txt", "ours\n", "deleted.txt", "ours edited\n",
                    "added.txt", "ours added\n");
            ObjectId theirs = tree("content.txt", "theirs\n", "added.txt", "theirs added\n");

            List<MergeConflict> conflicts = conflicted(merger.merge(base, ours, theirs)).conflicts();

            assertThat(conflicts).extracting(MergeConflict::path)
                    .containsExactly("added.txt", "content.txt", "deleted.txt");
            assertThat(conflicts).extracting(MergeConflict::kind).containsExactly(
                    ConflictKind.ADD_ADD, ConflictKind.CONTENT, ConflictKind.MODIFY_DELETE);
        }
    }

    @Nested
    @DisplayName("determinism")
    class Determinism {

        @Test
        void repeatingAConflictedMergeGivesTheSameConflicts() {
            ObjectId base = tree("a.txt", "base\n");
            ObjectId ours = tree("a.txt", "ours\n");
            ObjectId theirs = tree("a.txt", "theirs\n");

            assertThat(conflicted(merger.merge(base, ours, theirs)).conflicts())
                    .isEqualTo(conflicted(merger.merge(base, ours, theirs)).conflicts());
        }

        @Test
        void swappingSidesGivesTheSameConflictPathsWithRolesReversed() {
            ObjectId base = tree("a.txt", "base\n");
            ObjectId ours = tree("a.txt", "ours\n");
            ObjectId theirs = tree("a.txt", "theirs\n");

            MergeConflict forward = onlyConflict(merger.merge(base, ours, theirs));
            MergeConflict reversed = onlyConflict(merger.merge(base, theirs, ours));

            // Which side is "ours" is a role, not a fact about the trees, so the
            // disagreement is the same and only the labels swap.
            assertThat(reversed.path()).isEqualTo(forward.path());
            assertThat(reversed.kind()).isEqualTo(forward.kind());
            assertThat(reversed.ours()).isEqualTo(forward.theirs());
            assertThat(reversed.theirs()).isEqualTo(forward.ours());
        }

        @Test
        void conflictSidesCarryTheActualBlobIds() {
            ObjectId base = tree("a.txt", "base\n");
            ObjectId ours = tree("a.txt", "ours\n");
            ObjectId theirs = tree("a.txt", "theirs\n");

            MergeConflict conflict = onlyConflict(merger.merge(base, ours, theirs));

            // The reported ids must be resolvable, so a caller can show the
            // three versions side by side.
            assertThat(new String(store.readBlob(conflict.base().orElseThrow().id()).payload(),
                    StandardCharsets.UTF_8)).isEqualTo("base\n");
            assertThat(new String(store.readBlob(conflict.ours().orElseThrow().id()).payload(),
                    StandardCharsets.UTF_8)).isEqualTo("ours\n");
            assertThat(new String(store.readBlob(conflict.theirs().orElseThrow().id()).payload(),
                    StandardCharsets.UTF_8)).isEqualTo("theirs\n");
        }
    }
}
