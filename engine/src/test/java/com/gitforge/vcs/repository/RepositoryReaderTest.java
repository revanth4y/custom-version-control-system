package com.gitforge.vcs.repository;

import com.gitforge.vcs.diff.TreeChange;
import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Signature;
import com.gitforge.vcs.object.TreeEntry;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepositoryReaderTest {

    @TempDir
    Path storageRoot;

    private VcsRepository repository;
    private ObjectId first;
    private ObjectId second;

    private int sequence;

    @BeforeEach
    void setUp() {
        repository = new VcsRepositoryFactory(storageRoot).initialise(RepositoryId.of("demo"), "main");

        first = commit("Initial commit",
                FileChange.put("README.md", bytes("# Demo\n")),
                FileChange.put("src/App.java", bytes("app\n")),
                FileChange.put("src/deep/Util.java", bytes("util\n")));

        second = commit("Second commit",
                FileChange.put("README.md", bytes("# Demo v2\n")),
                FileChange.put("docs/guide.md", bytes("guide\n")));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private Signature signature() {
        return Signature.of("Ada", "ada@example.com", Instant.ofEpochSecond(1_700_000_000L + sequence++));
    }

    private ObjectId commit(String message, FileChange... changes) {
        return repository.commits().commit("main", List.of(changes), signature(), message);
    }

    @Nested
    @DisplayName("browsing")
    class Browsing {

        @Test
        void listsTheRepositoryRoot() {
            assertThat(repository.reader().listDirectory("main", "")).get()
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(TreeEntry.class))
                    .extracting(TreeEntry::name)
                    .containsExactly("README.md", "docs", "src");
        }

        @Test
        void listsANestedDirectory() {
            assertThat(repository.reader().listDirectory("main", "src")).get()
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(TreeEntry.class))
                    .extracting(TreeEntry::name)
                    .containsExactly("App.java", "deep");
        }

        @Test
        void listingAFileOrAMissingPathIsEmpty() {
            assertThat(repository.reader().listDirectory("main", "README.md")).isEmpty();
            assertThat(repository.reader().listDirectory("main", "nope")).isEmpty();
        }

        @Test
        void listingAnUnknownRevisionIsEmpty() {
            assertThat(repository.reader().listDirectory("no-such-branch", "")).isEmpty();
        }

        @Test
        void listsEveryFileInARevision() {
            assertThat(repository.reader().listAllFiles("main"))
                    .extracting(entry -> entry.path())
                    .containsExactly("README.md", "docs/guide.md", "src/App.java", "src/deep/Util.java");
        }
    }

    @Nested
    @DisplayName("reading files")
    class Reading {

        @Test
        void readsAFileAtTheRoot() {
            assertThat(repository.reader().readFile("main", "README.md")).get()
                    .isEqualTo(bytes("# Demo v2\n"));
        }

        @Test
        void readsANestedFile() {
            assertThat(repository.reader().readFile("main", "src/deep/Util.java")).get()
                    .isEqualTo(bytes("util\n"));
        }

        @Test
        void readsFromAnEarlierRevision() {
            // History is immutable, so the old content is still exactly there.
            assertThat(repository.reader().readFile(first.toHex(), "README.md")).get()
                    .isEqualTo(bytes("# Demo\n"));
        }

        @Test
        void readingADirectoryOrAMissingPathIsEmpty() {
            assertThat(repository.reader().readFile("main", "src")).isEmpty();
            assertThat(repository.reader().readFile("main", "ghost.txt")).isEmpty();
        }

        @Test
        void resolvesHeadBranchNamesAndCommitIds() {
            assertThat(repository.reader().resolve("HEAD")).contains(second);
            assertThat(repository.reader().resolve("main")).contains(second);
            assertThat(repository.reader().resolve(first.toHex())).contains(first);
            assertThat(repository.reader().resolve("nope")).isEmpty();
        }
    }

    @Nested
    @DisplayName("history")
    class History {

        @Test
        void listsCommitsNewestFirst() {
            assertThat(repository.reader().history("main", 10))
                    .extracting(Commit::message)
                    .containsExactly("Second commit\n", "Initial commit\n");
        }

        @Test
        void respectsTheLimit() {
            assertThat(repository.reader().history("main", 1))
                    .extracting(Commit::message).containsExactly("Second commit\n");
        }

        @Test
        void historyOfAnUnknownRevisionIsEmpty() {
            assertThat(repository.reader().history("nope", 10)).isEmpty();
        }

        @Test
        void rejectsANonPositiveLimit() {
            assertThatThrownBy(() -> repository.reader().history("main", 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void readsASingleCommit() {
            assertThat(repository.reader().commit(second)).get()
                    .extracting(Commit::message).isEqualTo("Second commit\n");
        }

        @Test
        void readingAnUnknownCommitIsEmpty() {
            assertThat(repository.reader().commit(ObjectId.fromHex("00".repeat(20)))).isEmpty();
        }

        @Test
        void hasCommitsReflectsWhetherAnythingIsRecorded() {
            assertThat(repository.reader().hasCommits()).isTrue();

            VcsRepository fresh = new VcsRepositoryFactory(storageRoot)
                    .initialise(RepositoryId.of("fresh"), "main");
            assertThat(fresh.reader().hasCommits()).isFalse();
        }
    }

    @Nested
    @DisplayName("diffs")
    class Diffs {

        @Test
        void reportsWhatACommitChanged() {
            var diff = repository.reader().changesIn(second);

            assertThat(diff.modified()).extracting(TreeChange.Modified::path).containsExactly("README.md");
            assertThat(diff.added()).extracting(TreeChange.Added::path).containsExactly("docs/guide.md");
        }

        @Test
        void anInitialCommitIsAllAdditions() {
            // Compared against the empty tree, so callers need no special case.
            assertThat(repository.reader().changesIn(first).added())
                    .extracting(TreeChange.Added::path)
                    .containsExactly("README.md", "src/App.java", "src/deep/Util.java");
        }

        @Test
        void comparesTwoRevisions() {
            assertThat(repository.reader().compare(first.toHex(), "main")).get()
                    .extracting(diff -> diff.paths())
                    .isEqualTo(java.util.Set.of("README.md", "docs/guide.md"));
        }

        @Test
        void comparingARevisionWithItselfIsEmpty() {
            assertThat(repository.reader().compare("main", "main")).get()
                    .extracting(diff -> diff.isEmpty()).isEqualTo(true);
        }

        @Test
        void comparingAnUnknownRevisionIsEmpty() {
            assertThat(repository.reader().compare("nope", "main")).isEmpty();
            assertThat(repository.reader().compare("main", "nope")).isEmpty();
        }
    }
}
