package com.gitforge.vcs.ref;

import com.gitforge.vcs.RepositoryFixture;
import com.gitforge.vcs.object.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BranchServiceTest {

    @TempDir
    Path tempDir;

    private Path repositoryRoot;
    private RepositoryFixture repository;
    private ObjectId initialCommit;
    private ObjectId secondCommit;

    @BeforeEach
    void setUp() {
        repositoryRoot = tempDir.resolve("repo");
        repository = new RepositoryFixture(repositoryRoot, tempDir.resolve("work"));

        initialCommit = repository.commit("Initial commit", null, "README.md", "# Demo\n");
        secondCommit = repository.commit("Second commit", initialCommit, "README.md", "# Demo v2\n");
        repository.branches().createBranch("main", secondCommit);
    }

    @Nested
    @DisplayName("creation")
    class Creation {

        @Test
        void createsABranchPointingAtACommit() {
            repository.branches().createBranch("feature", secondCommit);

            assertThat(repository.branches().getBranch("feature")).contains(secondCommit);
            assertThat(repository.branches().branchExists("feature")).isTrue();
        }

        @Test
        void branchingCopiesNoObjects() {
            long before = repository.objectStore().count();

            repository.branches().createBranch("feature", secondCommit);
            repository.branches().createBranch("another", initialCommit);

            // A branch is a name and a commit id; nothing about history is duplicated.
            assertThat(repository.objectStore().count()).isEqualTo(before);
        }

        @Test
        void aBranchFileHoldsOnlyTheCommitId() throws Exception {
            repository.branches().createBranch("feature", secondCommit);

            Path file = repositoryRoot.resolve("refs").resolve("heads").resolve("feature");

            assertThat(Files.readString(file)).isEqualTo(secondCommit.toHex() + "\n");
            assertThat(Files.size(file)).isEqualTo(41);
        }

        @Test
        void rejectsADuplicateName() {
            repository.branches().createBranch("feature", secondCommit);

            assertThatThrownBy(() -> repository.branches().createBranch("feature", initialCommit))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("already exists");
        }

        @Test
        void canPointAtTheInitialCommit() {
            repository.branches().createBranch("from-the-start", initialCommit);

            assertThat(repository.branches().getBranch("from-the-start")).contains(initialCommit);
        }

        @Test
        void severalBranchesMayShareOneCommit() {
            repository.branches().createBranch("alpha", secondCommit);
            repository.branches().createBranch("beta", secondCommit);

            assertThat(repository.branches().getBranch("alpha"))
                    .isEqualTo(repository.branches().getBranch("beta"))
                    .contains(secondCommit);
        }

        @Test
        void supportsNestedNames() {
            repository.branches().createBranch("feature/login", secondCommit);
            repository.branches().createBranch("feature/signup", initialCommit);

            assertThat(repository.branches().listBranches())
                    .contains("feature/login", "feature/signup");
            assertThat(repository.branches().getBranch("feature/login")).contains(secondCommit);
        }

        @Test
        void rejectsACommitThatIsNotStored() {
            ObjectId absent = ObjectId.fromHex("00".repeat(20));

            assertThatThrownBy(() -> repository.branches().createBranch("bad", absent))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("No such commit");
        }

        @Test
        void rejectsAnIdThatIsStoredButIsNotACommit() {
            ObjectId blobId = repository.objectStore()
                    .write(new com.gitforge.vcs.object.Blob(RepositoryFixture.bytes("not a commit")));

            assertThatThrownBy(() -> repository.branches().createBranch("bad", blobId))
                    .isInstanceOf(com.gitforge.vcs.object.CorruptObjectException.class);
        }

        @Test
        void createsFromAResolvableStartPoint() {
            repository.branches().createBranchFrom("from-main", "main");
            repository.branches().createBranchFrom("from-hex", initialCommit.toHex());

            assertThat(repository.branches().getBranch("from-main")).contains(secondCommit);
            assertThat(repository.branches().getBranch("from-hex")).contains(initialCommit);
        }

        @Test
        void rejectsAnUnresolvableStartPoint() {
            assertThatThrownBy(() -> repository.branches().createBranchFrom("nope", "no-such-branch"))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("Cannot resolve start point");
        }
    }

    @Nested
    @DisplayName("listing and lookup")
    class Listing {

        @Test
        void listsBranchesSorted() {
            repository.branches().createBranch("zebra", initialCommit);
            repository.branches().createBranch("alpha", initialCommit);

            assertThat(repository.branches().listBranches()).containsExactly("alpha", "main", "zebra");
        }

        @Test
        void lookupOfAnAbsentBranchIsEmpty() {
            assertThat(repository.branches().getBranch("ghost")).isEmpty();
            assertThat(repository.branches().branchExists("ghost")).isFalse();
        }
    }

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        void movesAnExistingBranch() {
            repository.branches().createBranch("feature", initialCommit);

            repository.branches().updateBranch("feature", secondCommit);

            assertThat(repository.branches().getBranch("feature")).contains(secondCommit);
        }

        @Test
        void refusesToUpdateAnAbsentBranch() {
            assertThatThrownBy(() -> repository.branches().updateBranch("ghost", secondCommit))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("does not exist");
        }

        @Test
        void refusesToPointAtAMissingCommit() {
            assertThatThrownBy(() -> repository.branches()
                    .updateBranch("main", ObjectId.fromHex("00".repeat(20))))
                    .isInstanceOf(RefException.class);
        }

        @Test
        void updateLeavesNoTemporaryFilesBehind() throws Exception {
            repository.branches().updateBranch("main", initialCommit);

            try (var paths = Files.walk(repositoryRoot.resolve("refs"))) {
                assertThat(paths.filter(Files::isRegularFile))
                        .allSatisfy(path -> assertThat(path.getFileName().toString()).doesNotStartWith(".tmp-"));
            }
        }
    }

    @Nested
    @DisplayName("deletion")
    class Deletion {

        @Test
        void removesTheBranch() {
            repository.branches().createBranch("feature", secondCommit);

            repository.branches().deleteBranch("feature");

            assertThat(repository.branches().branchExists("feature")).isFalse();
            assertThat(repository.branches().listBranches()).doesNotContain("feature");
        }

        @Test
        void refusesToDeleteAnAbsentBranch() {
            assertThatThrownBy(() -> repository.branches().deleteBranch("ghost"))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("does not exist");
        }

        @Test
        void refusesToDeleteTheCheckedOutBranch() {
            repository.refStore().setHead(Head.onBranch("main"));

            assertThatThrownBy(() -> repository.branches().deleteBranch("main"))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("checked-out branch");

            assertThat(repository.branches().branchExists("main")).isTrue();
        }

        @Test
        void deletingABranchKeepsEveryObject() {
            ObjectId tip = repository.commit("Only on this branch", secondCommit, "extra.txt", "content\n");
            repository.branches().createBranch("doomed", tip);
            long before = repository.objectStore().count();

            repository.branches().deleteBranch("doomed");

            // The history is now unreferenced but entirely intact: no other
            // branch reaches it, yet every object remains readable by id.
            assertThat(repository.objectStore().count()).isEqualTo(before);
            assertThat(repository.objectStore().contains(tip)).isTrue();
            assertThat(repository.objectStore().readCommit(tip).message()).isEqualTo("Only on this branch\n");
        }

        @Test
        void deletingANestedBranchRemovesItsEmptyDirectory() {
            repository.branches().createBranch("feature/login", secondCommit);

            repository.branches().deleteBranch("feature/login");

            assertThat(repositoryRoot.resolve("refs/heads/feature")).doesNotExist();
            assertThat(repositoryRoot.resolve("refs/heads")).isDirectory();
        }

        @Test
        void deletingOneNestedBranchKeepsItsSiblings() {
            repository.branches().createBranch("feature/login", secondCommit);
            repository.branches().createBranch("feature/signup", secondCommit);

            repository.branches().deleteBranch("feature/login");

            assertThat(repository.branches().listBranches()).contains("feature/signup");
        }
    }

    @Nested
    @DisplayName("name validation")
    class Names {

        @ParameterizedTest(name = "rejects \"{0}\"")
        @ValueSource(strings = {
                "", "   ",
                "/absolute", "trailing/",
                "double//slash",
                "..", "../escape", "feature/../../escape", "a/../b",
                ".hidden", "feature/.hidden",
                "-leading-dash",
                "has space", "has~tilde", "has^caret", "has:colon", "has?question",
                "has*star", "has[bracket", "back\\slash",
                "ref@{0}",
                "locked.lock",
                "HEAD",
                "C:/windows/path"
        })
        void rejectsUnsafeNames(String name) {
            assertThatThrownBy(() -> repository.branches().createBranch(name, secondCommit))
                    .isInstanceOf(RefException.class);
        }

        @Test
        void rejectsControlCharacters() {
            assertThatThrownBy(() -> repository.branches().createBranch("bad\nname", secondCommit))
                    .isInstanceOf(RefException.class);
        }

        // "main" is omitted deliberately: the fixture already creates it, and
        // its acceptability is established there.
        @ParameterizedTest(name = "accepts \"{0}\"")
        @ValueSource(strings = {"trunk", "feature/login", "release-1.0", "v2.1.3", "user/feature/deep", "a"})
        void acceptsReasonableNames(String name) {
            repository.branches().createBranch(name, secondCommit);

            assertThat(repository.branches().branchExists(name)).isTrue();
        }

        @Test
        void aTraversingNameCannotReachOutsideTheRefsDirectory() {
            assertThatThrownBy(() -> repository.branches()
                    .createBranch("../../objects/ff/malicious", secondCommit))
                    .isInstanceOf(RefException.class);

            assertThat(repositoryRoot.resolve("objects/ff/malicious")).doesNotExist();
        }
    }

    @Nested
    @DisplayName("HEAD")
    class HeadTracking {

        @Test
        void defaultsToTheDefaultBranchBeforeAnyHeadIsWritten() {
            assertThat(repository.refStore().readHead()).isEqualTo(Head.onBranch("main"));
        }

        @Test
        void resolvesThroughABranchToACommit() {
            repository.refStore().setHead(Head.onBranch("main"));

            assertThat(repository.refStore().resolveHead()).contains(secondCommit);
            assertThat(repository.branches().currentBranch()).contains("main");
        }

        @Test
        void followsTheBranchAsItMoves() {
            repository.refStore().setHead(Head.onBranch("main"));
            ObjectId third = repository.commit("Third", secondCommit, "README.md", "# v3\n");

            repository.branches().updateBranch("main", third);

            // HEAD names the branch, not the commit, so it advances with it.
            assertThat(repository.refStore().resolveHead()).contains(third);
        }

        @Test
        void resolvesToNothingWhenTheBranchDoesNotExistYet() {
            RepositoryFixture fresh = new RepositoryFixture(tempDir.resolve("fresh"), tempDir.resolve("fresh-work"));

            assertThat(fresh.refStore().readHead()).isEqualTo(Head.onBranch("main"));
            assertThat(fresh.refStore().resolveHead()).isEmpty();
        }

        @Test
        void supportsDetachedHead() {
            repository.refStore().setHead(Head.detachedAt(initialCommit));

            assertThat(repository.refStore().readHead()).isEqualTo(Head.detachedAt(initialCommit));
            assertThat(repository.refStore().readHead().isDetached()).isTrue();
            assertThat(repository.refStore().resolveHead()).contains(initialCommit);
            assertThat(repository.branches().currentBranch()).isEmpty();
        }

        @Test
        void headFileUsesTheDocumentedFormat() throws Exception {
            repository.refStore().setHead(Head.onBranch("main"));
            assertThat(Files.readString(repositoryRoot.resolve("HEAD"))).isEqualTo("ref: refs/heads/main\n");

            repository.refStore().setHead(Head.detachedAt(initialCommit));
            assertThat(Files.readString(repositoryRoot.resolve("HEAD"))).isEqualTo(initialCommit.toHex() + "\n");
        }

        @Test
        void rejectsAHeadPointingOutsideRefsHeads() throws Exception {
            Files.writeString(repositoryRoot.resolve("HEAD"), "ref: refs/tags/v1\n");

            assertThatThrownBy(() -> repository.refStore().readHead())
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("outside");
        }

        @Test
        void rejectsAMalformedHead() throws Exception {
            Files.writeString(repositoryRoot.resolve("HEAD"), "not a commit id\n");

            assertThatThrownBy(() -> repository.refStore().readHead())
                    .isInstanceOf(RefException.class);
        }
    }

    @Nested
    @DisplayName("revision resolution")
    class Resolution {

        @Test
        void resolvesHeadBranchNamesAndIds() {
            repository.refStore().setHead(Head.onBranch("main"));

            assertThat(repository.branches().resolve("HEAD")).contains(secondCommit);
            assertThat(repository.branches().resolve("main")).contains(secondCommit);
            assertThat(repository.branches().resolve(initialCommit.toHex())).contains(initialCommit);
        }

        @Test
        void returnsEmptyForUnknownRevisions() {
            assertThat(repository.branches().resolve("no-such-branch")).isEmpty();
            assertThat(repository.branches().resolve("00".repeat(20))).isEmpty();
            assertThat(repository.branches().resolve("has spaces")).isEmpty();
            assertThat(repository.branches().resolve(null)).isEmpty();
            assertThat(repository.branches().resolve("")).isEmpty();
        }
    }
}
