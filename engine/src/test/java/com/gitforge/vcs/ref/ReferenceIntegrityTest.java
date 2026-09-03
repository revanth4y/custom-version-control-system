package com.gitforge.vcs.ref;

import com.gitforge.vcs.RepositoryFixture;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.worktree.CheckoutBlockedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.gitforge.vcs.RepositoryFixture.files;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Properties that must hold across the boundary between the mutable reference
 * layer and the immutable object layer, and across process restarts.
 */
class ReferenceIntegrityTest {

    @TempDir
    Path tempDir;

    private Path repositoryRoot;
    private Path workRoot;
    private RepositoryFixture repository;
    private ObjectId initialCommit;
    private ObjectId secondCommit;

    @BeforeEach
    void setUp() {
        repositoryRoot = tempDir.resolve("repo");
        workRoot = tempDir.resolve("work");
        repository = new RepositoryFixture(repositoryRoot, workRoot);

        initialCommit = repository.commit("Initial commit", null, files(
                "README.md", "# Demo\n",
                "src/App.java", "class App {}\n"));
        secondCommit = repository.commit("Second commit", initialCommit, files(
                "README.md", "# Demo v2\n",
                "src/App.java", "class App {}\n"));

        repository.branches().createBranch("main", secondCommit);
    }

    /** Simulates a restart: everything is rebuilt from what is on disk. */
    private RepositoryFixture reopen() {
        return new RepositoryFixture(repositoryRoot, workRoot);
    }

    @Test
    @DisplayName("a branch always names a commit that exists")
    void branchesNameExistingCommits() {
        for (String branch : repository.branches().listBranches()) {
            ObjectId commit = repository.branches().getBranch(branch).orElseThrow();

            assertThat(repository.objectStore().contains(commit)).isTrue();
            assertThat(repository.objectStore().readCommit(commit)).isNotNull();
        }
    }

    @Test
    @DisplayName("references cannot point at objects that are not commits")
    void refusesNonCommitTargets() {
        ObjectId treeId = repository.objectStore().readCommit(secondCommit).tree();

        assertThatThrownBy(() -> repository.branches().createBranch("bad-tree", treeId))
                .isInstanceOf(com.gitforge.vcs.object.CorruptObjectException.class);
        assertThat(repository.branches().branchExists("bad-tree")).isFalse();
    }

    @Test
    @DisplayName("deleting a branch leaves every object intact")
    void deletingABranchPreservesObjects() {
        ObjectId tip = repository.commit("Only here", secondCommit, "extra.txt", "content\n");
        repository.branches().createBranch("temporary", tip);

        List<ObjectId> before = repository.objectStore().listIds();
        repository.branches().deleteBranch("temporary");

        // The reference is gone; the immutable history it named is untouched.
        assertThat(repository.objectStore().listIds()).containsExactlyInAnyOrderElementsOf(before);
        assertThat(repository.objectStore().readCommit(tip).message()).isEqualTo("Only here\n");
    }

    @Test
    @DisplayName("an unreferenced commit remains readable by id")
    void unreferencedHistoryStaysReadable() {
        ObjectId tip = repository.commit("Unreachable soon", secondCommit, "extra.txt", "content\n");
        repository.branches().createBranch("temporary", tip);
        repository.branches().deleteBranch("temporary");

        // No branch reaches it any more, but content addressing means it is
        // still perfectly retrievable — nothing was destroyed, only unnamed.
        assertThat(repository.branches().listBranches()).containsExactly("main");
        assertThat(repository.objectStore().contains(tip)).isTrue();
        assertThat(repository.branches().resolve(tip.toHex())).contains(tip);
    }

    @Test
    @DisplayName("a branch update replaces the file wholesale, never partially")
    void branchUpdateIsAtomic() throws IOException {
        Path branchFile = repositoryRoot.resolve("refs/heads/main");

        repository.branches().updateBranch("main", initialCommit);
        assertThat(Files.readString(branchFile)).isEqualTo(initialCommit.toHex() + "\n");

        repository.branches().updateBranch("main", secondCommit);
        String content = Files.readString(branchFile);

        // Exactly one complete id and nothing else: no remnant of the previous
        // value, no partial write.
        assertThat(content).isEqualTo(secondCommit.toHex() + "\n");
        assertThat(Files.size(branchFile)).isEqualTo(41);
    }

    @Test
    @DisplayName("no temporary files survive reference writes")
    void referenceWritesLeaveNoTemporaries() throws IOException {
        repository.branches().createBranch("feature/one", initialCommit);
        repository.branches().updateBranch("feature/one", secondCommit);
        repository.refStore().setHead(Head.onBranch("feature/one"));

        try (var paths = Files.walk(repositoryRoot)) {
            assertThat(paths.filter(Files::isRegularFile))
                    .allSatisfy(path -> assertThat(path.getFileName().toString()).doesNotStartWith(".tmp-"));
        }
    }

    @Test
    @DisplayName("a truncated reference is reported rather than silently misread")
    void aCorruptedReferenceIsRejected() throws IOException {
        Path branchFile = repositoryRoot.resolve("refs/heads/main");
        Files.writeString(branchFile, secondCommit.toHex().substring(0, 20), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> repository.branches().getBranch("main"))
                .isInstanceOf(RefException.class)
                .hasMessageContaining("valid commit id");
    }

    @Test
    @DisplayName("the repository is fully usable after a restart")
    void surviveRestart() throws IOException {
        repository.branches().createBranch("feature/login", initialCommit);
        repository.checkout().checkoutBranch("main");

        RepositoryFixture reopened = reopen();

        assertThat(reopened.branches().listBranches()).containsExactly("feature/login", "main");
        assertThat(reopened.branches().getBranch("main")).contains(secondCommit);
        assertThat(reopened.refStore().readHead()).isEqualTo(Head.onBranch("main"));
        assertThat(reopened.refStore().resolveHead()).contains(secondCommit);
        assertThat(reopened.objectStore().readCommit(secondCommit).message()).isEqualTo("Second commit\n");
        assertThat(Files.readString(workRoot.resolve("README.md"))).isEqualTo("# Demo v2\n");
    }

    @Test
    @DisplayName("working tree state survives a restart, so safety checks still apply")
    void workingTreeStateSurvivesRestart() throws IOException {
        repository.branches().createBranch("feature", initialCommit);
        repository.checkout().checkoutBranch("main");

        RepositoryFixture reopened = reopen();
        assertThat(reopened.checkout().status().isClean()).isTrue();

        Files.writeString(workRoot.resolve("README.md"), "locally edited\n", StandardCharsets.UTF_8);

        // The baseline was persisted, so a restarted process still recognises
        // the local edit and refuses to destroy it.
        assertThat(reopened.checkout().status().modified()).containsExactly("README.md");
        assertThatThrownBy(() -> reopened.checkout().checkoutBranch("feature"))
                .isInstanceOf(CheckoutBlockedException.class);
    }

    @Test
    @DisplayName("checkout after a restart continues to work")
    void checkoutAfterRestart() throws IOException {
        repository.branches().createBranch("feature", initialCommit);
        repository.checkout().checkoutBranch("main");

        RepositoryFixture reopened = reopen();
        reopened.checkout().checkoutBranch("feature");

        assertThat(Files.readString(workRoot.resolve("README.md"))).isEqualTo("# Demo\n");
        assertThat(reopened.refStore().readHead()).isEqualTo(Head.onBranch("feature"));
    }

    @Test
    @DisplayName("branches share history rather than copying it")
    void branchesShareUnderlyingObjects() {
        long before = repository.objectStore().count();

        for (int i = 0; i < 25; i++) {
            repository.branches().createBranch("branch-" + i, secondCommit);
        }

        assertThat(repository.objectStore().count()).isEqualTo(before);
        assertThat(repository.branches().listBranches()).hasSize(26);
    }

    @Test
    @DisplayName("the object store is never written to through a reference name")
    void referenceNamesCannotReachTheObjectStore() {
        long objectsBefore = repository.objectStore().count();

        assertThatThrownBy(() -> repository.branches()
                .createBranch("../../objects/aa/injected", secondCommit))
                .isInstanceOf(RefException.class);

        assertThat(repository.objectStore().count()).isEqualTo(objectsBefore);
        assertThat(repositoryRoot.resolve("objects/aa/injected")).doesNotExist();
    }
}
