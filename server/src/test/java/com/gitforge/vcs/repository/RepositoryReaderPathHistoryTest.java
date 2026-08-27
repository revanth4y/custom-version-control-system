package com.gitforge.vcs.repository;

import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The history of one path.
 *
 * <p>What makes this worth testing is everything other than a file that changes
 * in every commit: a directory has to be credited with changes beneath it, a
 * deleted file has to keep the history it had, a rename has to stop rather than
 * follow, and the two bounds have to stay distinct — a match eighty commits back
 * is still a match, and asking for three results must not read three commits and
 * give up.
 */
class RepositoryReaderPathHistoryTest {

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

    private Signature signature() {
        return Signature.of("Ada", "ada@example.com", Instant.ofEpochSecond(1_700_000_000L + sequence++));
    }

    private ObjectId commit(String branch, String message, FileChange... changes) {
        return repository.commits().commit(branch, List.of(changes), signature(), message);
    }

    private ObjectId commit(String message, FileChange... changes) {
        return commit("main", message, changes);
    }

    /** Subjects of the matching commits, newest first, without the stored newline. */
    private List<String> historyFor(String path, int limit, int window) {
        return repository.reader().historyForPath("main", path, limit, window).stream()
                .map(commit -> commit.message().strip())
                .toList();
    }

    private List<String> historyFor(String path) {
        return historyFor(path, 200, 200);
    }

    @Nested
    class OneFile {

        @Test
        void returnsOnlyTheCommitsThatTouchedIt() {
            commit("Add both",
                    FileChange.put("a.txt", bytes("1\n")),
                    FileChange.put("b.txt", bytes("1\n")));
            commit("Change a", FileChange.put("a.txt", bytes("2\n")));
            commit("Change b", FileChange.put("b.txt", bytes("2\n")));
            commit("Change a again", FileChange.put("a.txt", bytes("3\n")));

            assertThat(historyFor("a.txt"))
                    .containsExactly("Change a again", "Change a", "Add both");
        }

        @Test
        void ordersNewestFirst() {
            commit("First", FileChange.put("a.txt", bytes("1\n")));
            commit("Second", FileChange.put("a.txt", bytes("2\n")));
            commit("Third", FileChange.put("a.txt", bytes("3\n")));

            assertThat(historyFor("a.txt")).containsExactly("Third", "Second", "First");
        }

        @Test
        void aPathNeverTouchedHasNoHistory() {
            commit("Add a", FileChange.put("a.txt", bytes("1\n")));

            assertThat(historyFor("never-existed.txt")).isEmpty();
        }

        @Test
        void aDeletedFileKeepsTheHistoryItHad() {
            // Deleting a file does not delete its past. The removal is itself a
            // commit that touched the path, so it heads the list rather than
            // being the reason the list is empty.
            commit("Add a", FileChange.put("a.txt", bytes("1\n")));
            commit("Edit a", FileChange.put("a.txt", bytes("2\n")));
            commit("Remove a", FileChange.delete("a.txt"));

            assertThat(historyFor("a.txt")).containsExactly("Remove a", "Edit a", "Add a");
        }

        @Test
        void aFileInsideADirectoryIsFoundByItsFullPath() {
            commit("Add nested", FileChange.put("src/main/App.java", bytes("class A {}\n")));
            commit("Edit nested", FileChange.put("src/main/App.java", bytes("class B {}\n")));
            commit("Unrelated", FileChange.put("README.md", bytes("hi\n")));

            assertThat(historyFor("src/main/App.java")).containsExactly("Edit nested", "Add nested");
        }

        @Test
        void aPartialPathSegmentIsNotAMatch() {
            // "src/App" must not match "src/Application.java": the prefix test has
            // to respect the separator, or it credits unrelated files to a path.
            commit("Add Application", FileChange.put("src/Application.java", bytes("a\n")));

            assertThat(historyFor("src/App")).isEmpty();
            assertThat(historyFor("src/Application.java")).containsExactly("Add Application");
        }
    }

    @Nested
    class Directories {

        @Test
        void aDirectoryIsTouchedByChangesBeneathIt() {
            commit("Add nested", FileChange.put("src/App.java", bytes("a\n")));
            commit("Add other", FileChange.put("docs/guide.md", bytes("g\n")));
            commit("Edit nested", FileChange.put("src/App.java", bytes("b\n")));

            assertThat(historyFor("src")).containsExactly("Edit nested", "Add nested");
        }

        @Test
        void aDeeperDirectoryNarrowsFurther() {
            commit("Add deep", FileChange.put("src/main/App.java", bytes("a\n")));
            commit("Add shallow", FileChange.put("src/README.md", bytes("r\n")));

            assertThat(historyFor("src")).containsExactly("Add shallow", "Add deep");
            assertThat(historyFor("src/main")).containsExactly("Add deep");
        }

        @Test
        void theBlankPathIsTheRootAndSoIsTheWholeHistory() {
            commit("One", FileChange.put("a.txt", bytes("1\n")));
            commit("Two", FileChange.put("b.txt", bytes("1\n")));

            assertThat(historyFor("")).containsExactly("Two", "One");
            assertThat(repository.reader().historyForPath("main", null, 200, 200)).hasSize(2);
        }
    }

    @Nested
    class Renames {

        @Test
        void historyStopsAtTheRename() {
            /* There is no rename detection, so a move is a delete plus an add and
               nothing pairs the two. The new path's history therefore begins at
               the move. This asserts the documented limitation rather than
               working around it: if pairing is ever implemented, this test should
               fail and be rewritten, which is exactly the signal wanted. */
            commit("Add old", FileChange.put("old.txt", bytes("content\n")));
            commit("Edit old", FileChange.put("old.txt", bytes("more\n")));
            commit("Move old to new",
                    FileChange.delete("old.txt"),
                    FileChange.put("new.txt", bytes("more\n")));

            assertThat(historyFor("new.txt")).containsExactly("Move old to new");

            // The old path keeps everything up to and including the move.
            assertThat(historyFor("old.txt"))
                    .containsExactly("Move old to new", "Edit old", "Add old");
        }
    }

    @Nested
    class Merges {

        @Test
        void aMergeIsCreditedWithWhatItBroughtIn() {
            // Attribution goes through changesIn, which compares against the first
            // parent — so a merge that brings a file in counts as touching it,
            // matching how lastCommits credits the same commit.
            ObjectId base = commit("Base", FileChange.put("README.md", bytes("r\n")));
            repository.branches().createBranch("side", base);

            commit("side", "Add on side", FileChange.put("side.txt", bytes("s\n")));
            commit("main", "Add on main", FileChange.put("main.txt", bytes("m\n")));

            repository.merges().merge("main", "side", signature(), "Merge side");

            assertThat(historyFor("side.txt")).contains("Merge side", "Add on side");
        }
    }

    @Nested
    class Bounds {

        @Test
        void limitCapsHowManyMatchesComeBack() {
            commit("One", FileChange.put("a.txt", bytes("1\n")));
            commit("Two", FileChange.put("a.txt", bytes("2\n")));
            commit("Three", FileChange.put("a.txt", bytes("3\n")));

            assertThat(historyFor("a.txt", 2, 200)).containsExactly("Three", "Two");
        }

        @Test
        void theWindowIsSeparateFromTheLimit() {
            /* The whole reason two bounds exist. The file is touched once, at the
               very start, and then twenty commits go by without it. Asking for
               three results must not stop after reading three commits — with a
               wide enough window the match is still found. */
            commit("Touch the file", FileChange.put("rare.txt", bytes("once\n")));
            for (int i = 0; i < 20; i++) {
                commit("Noise " + i, FileChange.put("noise-" + i + ".txt", bytes("n\n")));
            }

            assertThat(historyFor("rare.txt", 3, 200)).containsExactly("Touch the file");
        }

        @Test
        void aMatchOutsideTheWindowIsReportedAsNothingFound() {
            /* And the honest converse: with a narrow window the same file comes
               back empty. That is "not touched within the window", not "never
               changed" — which is why the API says so and the interface repeats
               it rather than claiming the file has no history. */
            commit("Touch the file", FileChange.put("rare.txt", bytes("once\n")));
            for (int i = 0; i < 20; i++) {
                commit("Noise " + i, FileChange.put("noise-" + i + ".txt", bytes("n\n")));
            }

            assertThat(historyFor("rare.txt", 3, 5)).isEmpty();
        }

        @Test
        void nonPositiveBoundsAreRejected() {
            commit("One", FileChange.put("a.txt", bytes("1\n")));

            assertThatThrownBy(() -> repository.reader().historyForPath("main", "a.txt", 0, 10))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> repository.reader().historyForPath("main", "a.txt", 10, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void anUnknownRevisionHasNoHistory() {
            commit("One", FileChange.put("a.txt", bytes("1\n")));

            assertThat(repository.reader().historyForPath("no-such-branch", "a.txt", 10, 10)).isEmpty();
        }
    }
}
