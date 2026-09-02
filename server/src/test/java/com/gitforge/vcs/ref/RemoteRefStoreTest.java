package com.gitforge.vcs.ref;

import com.gitforge.vcs.RepositoryFixture;
import com.gitforge.vcs.object.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.gitforge.vcs.RepositoryFixture.files;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Remote-tracking refs, and the line between them and branches.
 *
 * <p>The property worth most here is separation. A tracking ref records what
 * someone else's branch looked like; if it leaks into the local branch list then
 * a fetch starts appearing as local work, and every count derived from branches
 * quietly becomes wrong.
 */
class RemoteRefStoreTest {

    @TempDir
    Path tempDir;

    private Path repositoryRoot;
    private RepositoryFixture repository;
    private RefStore refs;
    private ObjectId first;
    private ObjectId second;

    @BeforeEach
    void setUp() {
        repositoryRoot = tempDir.resolve("repo");
        repository = new RepositoryFixture(repositoryRoot, tempDir.resolve("work"));
        refs = repository.refStore();

        first = repository.commit("Initial commit", null, files("README.md", "# Demo\n"));
        second = repository.commit("Second commit", first, files("README.md", "# Demo v2\n"));
        repository.branches().createBranch("main", first);
    }

    @Nested
    @DisplayName("a tracking ref is not a branch")
    class Separation {

        @Test
        void trackingRefsDoNotAppearAmongBranches() {
            refs.setRemoteRef("origin", "main", second);

            assertThat(refs.listBranches()).containsExactly("main");
            assertThat(refs.branchExists("main")).isTrue();
            assertThat(refs.getBranch("main")).contains(first);
        }

        @Test
        void aTrackingRefMayShareABranchNameWithoutCollision() {
            refs.setRemoteRef("origin", "main", second);

            assertThat(refs.getBranch("main")).contains(first);
            assertThat(refs.getRemoteRef("origin", "main")).contains(second);
        }

        @Test
        void twoRemotesMayTrackTheSameBranchName() {
            refs.setRemoteRef("origin", "main", first);
            refs.setRemoteRef("backup", "main", second);

            assertThat(refs.getRemoteRef("origin", "main")).contains(first);
            assertThat(refs.getRemoteRef("backup", "main")).contains(second);
            assertThat(refs.listRemoteRefs()).extracting(RemoteRef::qualifiedName)
                    .containsExactly("backup/main", "origin/main");
        }

        @Test
        void trackingRefsLiveBesideHeadsOnDisk() {
            refs.setRemoteRef("origin", "main", second);

            assertThat(Files.isRegularFile(repositoryRoot.resolve("refs/remotes/origin/main"))).isTrue();
            assertThat(Files.isRegularFile(repositoryRoot.resolve("refs/heads/main"))).isTrue();
        }
    }

    @Nested
    @DisplayName("reading and writing")
    class ReadWrite {

        @Test
        void anUntrackedRefIsEmptyRatherThanAnError() {
            assertThat(refs.getRemoteRef("origin", "main")).isEmpty();
            assertThat(refs.listRemoteRefs()).isEmpty();
        }

        @Test
        void settingTwiceReplacesRatherThanRefusing() {
            refs.setRemoteRef("origin", "main", first);
            refs.setRemoteRef("origin", "main", second);

            assertThat(refs.getRemoteRef("origin", "main")).contains(second);
            assertThat(refs.listRemoteRefs()).hasSize(1);
        }

        @Test
        void nestedBranchNamesSurviveTheRoundTrip() {
            refs.setRemoteRef("origin", "feature/login", second);

            assertThat(refs.getRemoteRef("origin", "feature/login")).contains(second);
            assertThat(refs.listRemoteRefs()).extracting(RemoteRef::branch)
                    .containsExactly("feature/login");
            assertThat(refs.listRemoteRefs()).extracting(RemoteRef::qualifiedName)
                    .containsExactly("origin/feature/login");
        }

        @Test
        void aTrackingRefSurvivesReopeningTheStore() {
            refs.setRemoteRef("origin", "main", second);

            RefStore reopened = new FileSystemRefStore(repositoryRoot);

            assertThat(reopened.getRemoteRef("origin", "main")).contains(second);
        }

        @Test
        void deletingOneRefLeavesTheOthers() {
            refs.setRemoteRef("origin", "main", first);
            refs.setRemoteRef("origin", "develop", second);

            assertThat(refs.deleteRemoteRef("origin", "main")).isTrue();

            assertThat(refs.getRemoteRef("origin", "main")).isEmpty();
            assertThat(refs.getRemoteRef("origin", "develop")).contains(second);
        }

        @Test
        void deletingSomethingAbsentIsFalseRatherThanAnError() {
            assertThat(refs.deleteRemoteRef("origin", "main")).isFalse();
        }

        @Test
        void droppingARemoteRemovesEveryRefItHeld() {
            refs.setRemoteRef("origin", "main", first);
            refs.setRemoteRef("origin", "feature/login", second);
            refs.setRemoteRef("backup", "main", first);

            assertThat(refs.deleteRemoteRefs("origin")).isEqualTo(2);

            assertThat(refs.listRemoteRefs()).extracting(RemoteRef::qualifiedName)
                    .containsExactly("backup/main");
        }

        @Test
        void droppingARemoteLeavesEveryObjectInPlace() {
            refs.setRemoteRef("origin", "main", second);
            long before = repository.objectStore().count();

            refs.deleteRemoteRefs("origin");

            // Dropping references is not reclaiming storage. That stays a separate
            // thing somebody asks for, exactly as it is for branch deletion.
            assertThat(repository.objectStore().count()).isEqualTo(before);
            assertThat(repository.objectStore().contains(second)).isTrue();
        }
    }

    @Nested
    @DisplayName("names arriving from elsewhere are validated")
    class Names {

        @Test
        void aRemoteNameMayNotContainASlash() {
            assertThatThrownBy(() -> refs.setRemoteRef("origin/evil", "main", first))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("single segment");
        }

        @Test
        void aRemoteNameMayNotWalkUpTheTree() {
            assertThatThrownBy(() -> refs.setRemoteRef("..", "main", first))
                    .isInstanceOf(RefException.class);
            assertThatThrownBy(() -> refs.setRemoteRef(".", "main", first))
                    .isInstanceOf(RefException.class);
        }

        @Test
        void aBranchNameThatWouldEscapeIsRefused() {
            assertThatThrownBy(() -> refs.setRemoteRef("origin", "../../etc/passwd", first))
                    .isInstanceOf(RefException.class);
            assertThatThrownBy(() -> refs.setRemoteRef("origin", "/absolute", first))
                    .isInstanceOf(RefException.class);
        }

        @Test
        void nothingEscapesTheRemotesDirectory() throws IOException {
            // Whatever the naming rules admit, no write may land outside refs/remotes.
            refs.setRemoteRef("origin", "feature/login", second);

            try (var walk = Files.walk(repositoryRoot.resolve("refs/remotes"))) {
                assertThat(walk.filter(Files::isRegularFile))
                        .allSatisfy(path -> assertThat(path.normalize())
                                .startsWith(repositoryRoot.resolve("refs/remotes").normalize()));
            }
        }

        @Test
        void anEmptyOrOverlongRemoteNameIsRefused() {
            assertThatThrownBy(() -> refs.setRemoteRef("", "main", first))
                    .isInstanceOf(RefException.class);
            assertThatThrownBy(() -> refs.setRemoteRef("o".repeat(65), "main", first))
                    .isInstanceOf(RefException.class);
        }

        @Test
        void aTrackingRefMustNameACommit() {
            assertThatThrownBy(() -> refs.setRemoteRef("origin", "main", null))
                    .isInstanceOf(RefException.class);
        }

        @Test
        void anUnreadableRefFileIsSkippedRatherThanFailingTheWholeListing() throws IOException {
            refs.setRemoteRef("origin", "main", second);
            Path damaged = repositoryRoot.resolve("refs/remotes/origin/broken");
            Files.writeString(damaged, "not a commit id\n", StandardCharsets.UTF_8);

            // One unreadable entry must not hide the refs that are fine, which is
            // when the listing is most worth having.
            assertThat(refs.listRemoteRefs()).extracting(RemoteRef::qualifiedName)
                    .containsExactly("origin/main");
        }
    }
}
