package com.gitforge.vcs.worktree;

import com.gitforge.vcs.RepositoryFixture;
import com.gitforge.vcs.RepositoryFixture.FileSpec;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.ref.Head;
import com.gitforge.vcs.ref.RefException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;

import static com.gitforge.vcs.RepositoryFixture.bytes;
import static com.gitforge.vcs.RepositoryFixture.files;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckoutServiceTest {

    @TempDir
    Path tempDir;

    private Path workRoot;
    private RepositoryFixture repository;
    private ObjectId mainCommit;
    private ObjectId featureCommit;

    @BeforeEach
    void setUp() {
        workRoot = tempDir.resolve("work");
        repository = new RepositoryFixture(tempDir.resolve("repo"), workRoot);

        //  main:    README.md, src/App.java
        //  feature: README.md (changed), src/App.java, src/main/Deep.java
        mainCommit = repository.commit("Initial commit", null, files(
                "README.md", "# Demo\n",
                "src/App.java", "class App {}\n"));

        featureCommit = repository.commit("Feature work", mainCommit, files(
                "README.md", "# Demo, revised\n",
                "src/App.java", "class App {}\n",
                "src/main/Deep.java", "class Deep {}\n"));

        repository.branches().createBranch("main", mainCommit);
        repository.branches().createBranch("feature", featureCommit);
    }

    private String read(String path) throws IOException {
        return Files.readString(workRoot.resolve(path), StandardCharsets.UTF_8);
    }

    private void write(String path, String content) throws IOException {
        Path file = workRoot.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("materialization")
    class Materialization {

        @Test
        void initialCheckoutWritesEveryFile() throws IOException {
            repository.checkout().checkoutBranch("main");

            assertThat(read("README.md")).isEqualTo("# Demo\n");
            assertThat(read("src/App.java")).isEqualTo("class App {}\n");
            assertThat(repository.workingTree().listFiles())
                    .containsExactlyInAnyOrder("README.md", "src/App.java");
        }

        @Test
        void createsNestedDirectories() throws IOException {
            repository.checkout().checkoutBranch("feature");

            assertThat(workRoot.resolve("src/main")).isDirectory();
            assertThat(read("src/main/Deep.java")).isEqualTo("class Deep {}\n");
        }

        @Test
        void materializesBinaryContentByteForByte() {
            byte[] binary = new byte[256];
            for (int i = 0; i < binary.length; i++) {
                binary[i] = (byte) i;
            }
            ObjectId commit = repository.commit("Binary", null, files("data.bin", FileSpec.binary(binary)));
            repository.branches().createBranch("binary", commit);

            repository.checkout().checkoutBranch("binary");

            assertThat(workRoot.resolve("data.bin")).hasBinaryContent(binary);
        }

        @Test
        void materializesEmptyFiles() {
            ObjectId commit = repository.commit("Empty", null, files("empty.txt", FileSpec.binary(new byte[0])));
            repository.branches().createBranch("empty", commit);

            repository.checkout().checkoutBranch("empty");

            assertThat(workRoot.resolve("empty.txt")).exists().isEmptyFile();
        }

        @Test
        void materializesExecutableFileContent() throws IOException {
            ObjectId commit = repository.commit("Script", null,
                    files("run.sh", FileSpec.executable("#!/bin/sh\necho hi\n")));
            repository.branches().createBranch("script", commit);

            repository.checkout().checkoutBranch("script");

            // Content is asserted on every platform; the permission bit is not
            // available on filesystems without POSIX support.
            assertThat(read("run.sh")).isEqualTo("#!/bin/sh\necho hi\n");
        }

        @Test
        @EnabledOnOs({OS.LINUX, OS.MAC})
        void setsTheExecutableBitWherePosixIsSupported() throws IOException {
            ObjectId commit = repository.commit("Script", null, files(
                    "run.sh", FileSpec.executable("#!/bin/sh\n"),
                    "plain.txt", FileSpec.of("text\n")));
            repository.branches().createBranch("script", commit);

            repository.checkout().checkoutBranch("script");

            assertThat(Files.getPosixFilePermissions(workRoot.resolve("run.sh")))
                    .contains(PosixFilePermission.OWNER_EXECUTE);
            assertThat(Files.getPosixFilePermissions(workRoot.resolve("plain.txt")))
                    .doesNotContain(PosixFilePermission.OWNER_EXECUTE);
        }

        @Test
        void checkingOutAnEmptyCommitLeavesAnEmptyWorkingTree() {
            ObjectId commit = repository.commit("Nothing", null, files());
            repository.branches().createBranch("nothing", commit);

            repository.checkout().checkoutBranch("nothing");

            assertThat(repository.workingTree().listFiles()).isEmpty();
        }
    }

    @Nested
    @DisplayName("switching branches")
    class Switching {

        @Test
        void replacesChangedFilesAndAddsNewOnes() throws IOException {
            repository.checkout().checkoutBranch("main");
            repository.checkout().checkoutBranch("feature");

            assertThat(read("README.md")).isEqualTo("# Demo, revised\n");
            assertThat(read("src/main/Deep.java")).isEqualTo("class Deep {}\n");
        }

        @Test
        void removesFilesAbsentFromTheTargetBranch() {
            repository.checkout().checkoutBranch("feature");
            assertThat(workRoot.resolve("src/main/Deep.java")).exists();

            repository.checkout().checkoutBranch("main");

            // Tracked by feature, absent from main, so it must go.
            assertThat(workRoot.resolve("src/main/Deep.java")).doesNotExist();
            assertThat(repository.workingTree().listFiles())
                    .containsExactlyInAnyOrder("README.md", "src/App.java");
        }

        @Test
        void removesDirectoriesLeftEmpty() {
            repository.checkout().checkoutBranch("feature");

            repository.checkout().checkoutBranch("main");

            assertThat(workRoot.resolve("src/main")).doesNotExist();
            // src/ still holds App.java, so it survives.
            assertThat(workRoot.resolve("src")).isDirectory();
        }

        @Test
        void switchingBackAndForthIsStable() throws IOException {
            repository.checkout().checkoutBranch("main");
            repository.checkout().checkoutBranch("feature");
            repository.checkout().checkoutBranch("main");

            assertThat(read("README.md")).isEqualTo("# Demo\n");
            assertThat(repository.checkout().status().isClean()).isTrue();
        }

        @Test
        void leavesUnrelatedUntrackedFilesAlone() throws IOException {
            repository.checkout().checkoutBranch("main");
            write("scratch.txt", "my notes\n");

            repository.checkout().checkoutBranch("feature");

            // Not ours to delete: it collides with nothing in the target.
            assertThat(read("scratch.txt")).isEqualTo("my notes\n");
        }

        @Test
        void refusesToCheckOutAnAbsentBranch() {
            assertThatThrownBy(() -> repository.checkout().checkoutBranch("ghost"))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("does not exist");
        }
    }

    @Nested
    @DisplayName("HEAD updates")
    class HeadUpdates {

        @Test
        void attachesHeadToTheCheckedOutBranch() {
            repository.checkout().checkoutBranch("feature");

            assertThat(repository.refStore().readHead()).isEqualTo(Head.onBranch("feature"));
            assertThat(repository.branches().currentBranch()).contains("feature");
            assertThat(repository.refStore().resolveHead()).contains(featureCommit);
        }

        @Test
        void checkingOutACommitDetachesHead() throws IOException {
            repository.checkout().checkoutCommit(mainCommit);

            assertThat(repository.refStore().readHead().isDetached()).isTrue();
            assertThat(repository.refStore().resolveHead()).contains(mainCommit);
            assertThat(repository.branches().currentBranch()).isEmpty();
            assertThat(read("README.md")).isEqualTo("# Demo\n");
        }

        @Test
        void reattachesHeadWhenSwitchingBackToABranch() {
            repository.checkout().checkoutCommit(featureCommit);

            repository.checkout().checkoutBranch("main");

            assertThat(repository.refStore().readHead()).isEqualTo(Head.onBranch("main"));
        }

        @Test
        void headIsUnchangedWhenCheckoutIsRefused() throws IOException {
            repository.checkout().checkoutBranch("main");
            write("README.md", "locally edited\n");

            assertThatThrownBy(() -> repository.checkout().checkoutBranch("feature"))
                    .isInstanceOf(CheckoutBlockedException.class);

            // A refused checkout must change nothing at all.
            assertThat(repository.refStore().readHead()).isEqualTo(Head.onBranch("main"));
            assertThat(read("README.md")).isEqualTo("locally edited\n");
        }

        @Test
        void refusesToCheckOutSomethingThatIsNotACommit() {
            ObjectId blobId = repository.objectStore()
                    .write(new com.gitforge.vcs.object.Blob(bytes("not a commit")));

            assertThatThrownBy(() -> repository.checkout().checkoutCommit(blobId))
                    .isInstanceOf(com.gitforge.vcs.object.CorruptObjectException.class);
        }
    }

    @Nested
    @DisplayName("working tree safety")
    class Safety {

        @Test
        void refusesWhenATrackedFileWasModified() throws IOException {
            repository.checkout().checkoutBranch("main");
            write("README.md", "locally edited\n");

            assertThatThrownBy(() -> repository.checkout().checkoutBranch("feature"))
                    .isInstanceOf(CheckoutBlockedException.class)
                    .hasMessageContaining("README.md");
        }

        @Test
        void refusesWhenATrackedFileWasDeleted() throws IOException {
            repository.checkout().checkoutBranch("main");
            Files.delete(workRoot.resolve("README.md"));

            assertThatThrownBy(() -> repository.checkout().checkoutBranch("feature"))
                    .isInstanceOf(CheckoutBlockedException.class)
                    .hasMessageContaining("deleted");
        }

        @Test
        void refusesWhenAnUntrackedFileCollidesWithAnIncomingOne() throws IOException {
            repository.checkout().checkoutBranch("main");
            // Not tracked on main, but feature will need to write here.
            write("src/main/Deep.java", "my own version\n");

            assertThatThrownBy(() -> repository.checkout().checkoutBranch("feature"))
                    .isInstanceOf(CheckoutBlockedException.class)
                    .hasMessageContaining("untracked");
        }

        @Test
        void aRefusedCheckoutLeavesTheWorkingTreeUntouched() throws IOException {
            repository.checkout().checkoutBranch("main");
            write("README.md", "locally edited\n");

            assertThatThrownBy(() -> repository.checkout().checkoutBranch("feature"))
                    .isInstanceOf(CheckoutBlockedException.class);

            assertThat(read("README.md")).isEqualTo("locally edited\n");
            assertThat(workRoot.resolve("src/main/Deep.java")).doesNotExist();
        }

        @Test
        void blockedCheckoutReportsTheOffendingPaths() throws IOException {
            repository.checkout().checkoutBranch("main");
            write("README.md", "locally edited\n");
            Files.delete(workRoot.resolve("src/App.java"));

            CheckoutBlockedException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                    CheckoutBlockedException.class,
                    () -> repository.checkout().checkoutBranch("feature"));

            assertThat(thrown.status().modified()).containsExactly("README.md");
            assertThat(thrown.status().deleted()).containsExactly("src/App.java");
        }

        @Test
        void allowsCheckoutOnceLocalChangesAreReverted() throws IOException {
            repository.checkout().checkoutBranch("main");
            write("README.md", "locally edited\n");
            assertThatThrownBy(() -> repository.checkout().checkoutBranch("feature"))
                    .isInstanceOf(CheckoutBlockedException.class);

            write("README.md", "# Demo\n");
            repository.checkout().checkoutBranch("feature");

            assertThat(read("README.md")).isEqualTo("# Demo, revised\n");
        }

        @Test
        void modificationIsDetectedByContentNotBySize() throws IOException {
            repository.checkout().checkoutBranch("main");
            // Same byte count, different bytes: a size or timestamp check would
            // miss this, a content hash cannot.
            write("README.md", "# DEMO\n");

            assertThatThrownBy(() -> repository.checkout().checkoutBranch("feature"))
                    .isInstanceOf(CheckoutBlockedException.class);
        }
    }

    @Nested
    @DisplayName("status")
    class Status {

        @Test
        void isCleanImmediatelyAfterCheckout() {
            repository.checkout().checkoutBranch("main");

            assertThat(repository.checkout().status().isClean()).isTrue();
        }

        @Test
        void reportsModificationsDeletionsAndUntrackedFiles() throws IOException {
            repository.checkout().checkoutBranch("feature");
            write("README.md", "edited\n");
            Files.delete(workRoot.resolve("src/App.java"));
            write("notes.txt", "scratch\n");

            WorkingTreeStatus status = repository.checkout().status();

            assertThat(status.modified()).containsExactly("README.md");
            assertThat(status.deleted()).containsExactly("src/App.java");
            assertThat(status.untracked()).containsExactly("notes.txt");
            assertThat(status.isClean()).isFalse();
            assertThat(status.hasLocalChanges()).isTrue();
        }

        @Test
        void untrackedFilesAloneAreNotLocalChanges() throws IOException {
            repository.checkout().checkoutBranch("main");
            write("notes.txt", "scratch\n");

            WorkingTreeStatus status = repository.checkout().status();

            assertThat(status.hasLocalChanges()).isFalse();
            assertThat(status.isClean()).isFalse();
            assertThat(status.untracked()).containsExactly("notes.txt");
        }

        @Test
        void reportsEverythingAsUntrackedBeforeAnyCheckout() throws IOException {
            write("stray.txt", "content\n");

            assertThat(repository.checkout().status().untracked()).containsExactly("stray.txt");
        }
    }
}
