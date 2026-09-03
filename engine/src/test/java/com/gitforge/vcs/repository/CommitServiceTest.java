package com.gitforge.vcs.repository;

import com.gitforge.vcs.CountingObjectStore;
import com.gitforge.vcs.ExplodingObjectStore;
import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.FileMode;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.ObjectType;
import com.gitforge.vcs.object.Signature;
import com.gitforge.vcs.ref.FileSystemRefStore;
import com.gitforge.vcs.ref.RefStore;
import com.gitforge.vcs.storage.FileSystemObjectStore;
import com.gitforge.vcs.storage.ObjectStore;
import com.gitforge.vcs.storage.ObjectStoreException;
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

class CommitServiceTest {

    @TempDir
    Path storageRoot;

    private VcsRepository repository;

    private static final Signature ADA =
            Signature.of("Ada Lovelace", "ada@example.com", Instant.ofEpochSecond(1_700_000_000L));

    @BeforeEach
    void setUp() {
        repository = new VcsRepositoryFactory(storageRoot).initialise(RepositoryId.of("demo"), "main");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private ObjectId commit(String message, FileChange... changes) {
        return repository.commits().commit("main", List.of(changes), ADA, message);
    }

    private String read(String path) {
        return new String(repository.reader().readFile("main", path).orElseThrow(), StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("creating commits")
    class Creating {

        @Test
        void theFirstCommitCreatesTheBranchHeadNames() {
            assertThat(repository.branches().listBranches()).isEmpty();

            ObjectId commitId = commit("Initial commit", FileChange.put("README.md", bytes("# Demo\n")));

            assertThat(repository.branches().listBranches()).containsExactly("main");
            assertThat(repository.branches().getBranch("main")).contains(commitId);
            assertThat(repository.objects().readCommit(commitId).isInitial()).isTrue();
        }

        @Test
        void commitsChainToTheirParent() {
            ObjectId first = commit("Initial commit", FileChange.put("a.txt", bytes("a\n")));
            ObjectId second = commit("Second commit", FileChange.put("b.txt", bytes("b\n")));

            assertThat(repository.objects().readCommit(second).parents()).containsExactly(first);
            assertThat(repository.branches().getBranch("main")).contains(second);
        }

        @Test
        void recordsSeveralFilesInOneCommit() {
            commit("Initial commit",
                    FileChange.put("README.md", bytes("# Demo\n")),
                    FileChange.put("src/App.java", bytes("app\n")),
                    FileChange.put("src/deep/Util.java", bytes("util\n")));

            assertThat(read("README.md")).isEqualTo("# Demo\n");
            assertThat(read("src/App.java")).isEqualTo("app\n");
            assertThat(read("src/deep/Util.java")).isEqualTo("util\n");
        }

        @Test
        void appliesAdditionsModificationsAndDeletionsTogether() {
            commit("Initial commit",
                    FileChange.put("keep.txt", bytes("keep\n")),
                    FileChange.put("edit.txt", bytes("old\n")),
                    FileChange.put("gone.txt", bytes("bye\n")));

            commit("Mixed change",
                    FileChange.put("edit.txt", bytes("new\n")),
                    FileChange.delete("gone.txt"),
                    FileChange.put("fresh.txt", bytes("hi\n")));

            assertThat(repository.reader().listAllFiles("main"))
                    .extracting(entry -> entry.path())
                    .containsExactly("edit.txt", "fresh.txt", "keep.txt");
            assertThat(read("edit.txt")).isEqualTo("new\n");
        }

        @Test
        void deletesAFile() {
            commit("Initial commit",
                    FileChange.put("a.txt", bytes("a\n")),
                    FileChange.put("b.txt", bytes("b\n")));

            commit("Remove b", FileChange.delete("b.txt"));

            assertThat(repository.reader().readFile("main", "b.txt")).isEmpty();
            assertThat(read("a.txt")).isEqualTo("a\n");
        }

        @Test
        void roundTripsBinaryContentByteForByte() {
            byte[] binary = new byte[256];
            for (int i = 0; i < binary.length; i++) {
                binary[i] = (byte) i;
            }

            commit("Add binary", FileChange.put("data.bin", binary));

            assertThat(repository.reader().readFile("main", "data.bin")).get().isEqualTo(binary);
        }

        @Test
        void storesEmptyFiles() {
            commit("Add empty", FileChange.put("empty.txt", new byte[0]));

            assertThat(repository.reader().readFile("main", "empty.txt")).get()
                    .isEqualTo(new byte[0]);
        }

        @Test
        void preservesExecutableMode() {
            commit("Add script",
                    FileChange.put("run.sh", bytes("#!/bin/sh\n"), FileMode.EXECUTABLE_FILE));

            assertThat(repository.reader().entryAt("main", "run.sh")).get()
                    .extracting(entry -> entry.mode()).isEqualTo(FileMode.EXECUTABLE_FILE);
        }

        @Test
        void recordsAuthorCommitterTimestampAndMessage() {
            Signature author = Signature.of("Ada", "ada@example.com", Instant.ofEpochSecond(1_700_000_500L));
            Signature committer = Signature.of("Grace", "grace@example.com", Instant.ofEpochSecond(1_700_000_600L));

            ObjectId commitId = repository.commits().commit(
                    "main", List.of(FileChange.put("a.txt", bytes("a\n"))), author, committer, "Detailed message");

            Commit stored = repository.objects().readCommit(commitId);
            assertThat(stored.author()).isEqualTo(author);
            assertThat(stored.committer()).isEqualTo(committer);
            assertThat(stored.message()).isEqualTo("Detailed message\n");
        }

        @Test
        void commitsAreContentAddressed() {
            ObjectId commitId = commit("Initial commit", FileChange.put("a.txt", bytes("a\n")));

            // The id is the hash of the commit, not a generated identifier.
            assertThat(repository.objects().readCommit(commitId).id()).isEqualTo(commitId);
            repository.objects().verify(commitId);
        }

        @Test
        void commitsOnAnotherBranchLeaveMainAlone() {
            ObjectId mainTip = commit("Initial commit", FileChange.put("a.txt", bytes("a\n")));
            repository.branches().createBranchFrom("feature", "main");

            repository.commits().commit("feature",
                    List.of(FileChange.put("b.txt", bytes("b\n"))), ADA, "Feature work");

            assertThat(repository.branches().getBranch("main")).contains(mainTip);
            assertThat(repository.branches().getBranch("feature")).isNotEqualTo(
                    repository.branches().getBranch("main"));
        }
    }

    @Nested
    @DisplayName("sparse rebuilding")
    class Sparse {

        @Test
        void changingOneFileRewritesOnlyItsDirectorySpine() {
            CountingObjectStore counting = new CountingObjectStore(new FileSystemObjectStore(storageRoot.resolve("s")));
            RefStore refs = new FileSystemRefStore(storageRoot.resolve("s"));
            VcsRepository sparse = new VcsRepository(RepositoryId.of("s"), counting, refs);
            refs.setHead(com.gitforge.vcs.ref.Head.onBranch("main"));

            sparse.commits().commit("main", List.of(
                    FileChange.put("src/App.java", bytes("app\n")),
                    FileChange.put("docs/deep/guide.md", bytes("guide\n"))), ADA, "Initial commit");

            ObjectId docsBefore = counting.readTree(
                    counting.readCommit(sparse.branches().getBranch("main").orElseThrow()).tree())
                    .entry("docs").orElseThrow().id();
            counting.resetCounts();

            sparse.commits().commit("main",
                    List.of(FileChange.put("src/App.java", bytes("changed\n"))), ADA, "Edit app");

            // docs/ is untouched, so it is neither read nor rewritten.
            assertThat(counting.hasRead(docsBefore)).isFalse();
            // Written: the blob, the src tree, the root tree, and the commit.
            assertThat(counting.writeCount()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("rejected commits")
    class Rejected {

        @Test
        void refusesACommitThatChangesNothing() {
            commit("Initial commit", FileChange.put("a.txt", bytes("a\n")));

            // Writing identical bytes produces an identical root tree, so there
            // is genuinely nothing to record.
            assertThatThrownBy(() -> commit("No-op", FileChange.put("a.txt", bytes("a\n"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Nothing to commit");
        }

        @Test
        void refusesAnEmptyChangeSet() {
            assertThatThrownBy(() -> repository.commits().commit("main", List.of(), ADA, "Nothing"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least one change");
        }

        @Test
        void refusesToDeleteAPathThatDoesNotExist() {
            commit("Initial commit", FileChange.put("a.txt", bytes("a\n")));

            assertThatThrownBy(() -> commit("Bad delete", FileChange.delete("ghost.txt")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not exist");
        }

        @Test
        void refusesPathTraversal() {
            assertThatThrownBy(() -> commit("Escape", FileChange.put("../escape.txt", bytes("x\n"))))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void refusesAMissingBranchName() {
            assertThatThrownBy(() -> repository.commits().commit(
                    "", List.of(FileChange.put("a.txt", bytes("a\n"))), ADA, "x"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("failure safety")
    class FailureSafety {

        private VcsRepository explodingRepository(ExplodingObjectStore store, Path root) {
            RefStore refs = new FileSystemRefStore(root);
            refs.setHead(com.gitforge.vcs.ref.Head.onBranch("main"));
            return new VcsRepository(RepositoryId.of("boom"), store, refs);
        }

        @Test
        void aFailureWritingTheCommitLeavesTheBranchWhereItWas() {
            Path root = storageRoot.resolve("boom");
            ObjectStore backing = new FileSystemObjectStore(root);
            ExplodingObjectStore store = new ExplodingObjectStore(backing);
            VcsRepository boom = explodingRepository(store, root);

            ObjectId original = boom.commits().commit("main",
                    List.of(FileChange.put("a.txt", bytes("a\n"))), ADA, "Initial commit");

            // Blobs and trees persist; the commit itself does not.
            store.failOnWritingType(ObjectType.COMMIT);

            assertThatThrownBy(() -> boom.commits().commit("main",
                    List.of(FileChange.put("a.txt", bytes("changed\n"))), ADA, "Doomed"))
                    .isInstanceOf(ObjectStoreException.class);

            // The branch never moved, because it is updated only after the
            // commit is durable.
            assertThat(boom.branches().getBranch("main")).contains(original);
            assertThat(new String(boom.reader().readFile("main", "a.txt").orElseThrow(),
                    StandardCharsets.UTF_8)).isEqualTo("a\n");
        }

        @Test
        void aFailureWritingATreeLeavesTheBranchWhereItWas() {
            Path root = storageRoot.resolve("boom2");
            ExplodingObjectStore store = new ExplodingObjectStore(new FileSystemObjectStore(root));
            VcsRepository boom = explodingRepository(store, root);

            ObjectId original = boom.commits().commit("main",
                    List.of(FileChange.put("src/App.java", bytes("app\n"))), ADA, "Initial commit");

            store.failOnWritingType(ObjectType.TREE);

            assertThatThrownBy(() -> boom.commits().commit("main",
                    List.of(FileChange.put("src/App.java", bytes("changed\n"))), ADA, "Doomed"))
                    .isInstanceOf(ObjectStoreException.class);

            assertThat(boom.branches().getBranch("main")).contains(original);
        }

        @Test
        void theRepositoryStillWorksAfterAFailedCommit() {
            Path root = storageRoot.resolve("boom3");
            ExplodingObjectStore store = new ExplodingObjectStore(new FileSystemObjectStore(root));
            VcsRepository boom = explodingRepository(store, root);

            boom.commits().commit("main", List.of(FileChange.put("a.txt", bytes("a\n"))), ADA, "Initial");

            store.failOnWritingType(ObjectType.COMMIT);
            assertThatThrownBy(() -> boom.commits().commit("main",
                    List.of(FileChange.put("a.txt", bytes("changed\n"))), ADA, "Doomed"))
                    .isInstanceOf(ObjectStoreException.class);

            // Orphaned blobs and trees are harmless: they are immutable, unnamed,
            // and content-addressed, so the retry simply reuses them.
            store.defuse();
            ObjectId retried = boom.commits().commit("main",
                    List.of(FileChange.put("a.txt", bytes("changed\n"))), ADA, "Retried");

            assertThat(boom.branches().getBranch("main")).contains(retried);
            assertThat(new String(boom.reader().readFile("main", "a.txt").orElseThrow(),
                    StandardCharsets.UTF_8)).isEqualTo("changed\n");
        }
    }
}
