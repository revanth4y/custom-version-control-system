package com.gitforge.vcs.ref;

import com.gitforge.vcs.object.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tags as stored references.
 *
 * <p>Two properties matter more than the rest and are tested hardest: a tag lives
 * beside branches rather than among them, so listing branches never shows one;
 * and a tag cannot be moved, which is enforced by the store offering no operation
 * that would move it.
 */
class TagRefStoreTest {

    @TempDir
    Path repositoryRoot;

    private FileSystemRefStore refs;

    private static final ObjectId TARGET =
            ObjectId.fromHex("a94a8fe5ccb19ba61c4c0873d391e987982fbbd3");
    private static final ObjectId OTHER =
            ObjectId.fromHex("da39a3ee5e6b4b0d3255bfef95601890afd80709");

    @BeforeEach
    void setUp() {
        refs = new FileSystemRefStore(repositoryRoot);
    }

    @Nested
    @DisplayName("storage and retrieval")
    class Storage {

        @Test
        void aRepositoryWithNoTagsListsNone() {
            assertThat(refs.listTags()).isEmpty();
            assertThat(refs.getTag("v1")).isEmpty();
            assertThat(refs.tagExists("v1")).isFalse();
        }

        @Test
        void aCreatedTagIsReadBack() {
            refs.createTag("v1.0.0", TARGET);

            assertThat(refs.getTag("v1.0.0")).contains(TARGET);
            assertThat(refs.tagExists("v1.0.0")).isTrue();
            assertThat(refs.listTags()).containsExactly("v1.0.0");
        }

        @Test
        void aTagIsStoredUnderRefsTags() {
            refs.createTag("v1.0.0", TARGET);

            Path file = repositoryRoot.resolve("refs").resolve("tags").resolve("v1.0.0");

            assertThat(file).exists();
            assertThat(Files.exists(repositoryRoot.resolve("refs").resolve("heads").resolve("v1.0.0")))
                    .isFalse();
        }

        @Test
        void theFileHoldsTheHexIdAndANewline() throws Exception {
            refs.createTag("v1", TARGET);

            String content = Files.readString(
                    repositoryRoot.resolve("refs/tags/v1"), StandardCharsets.UTF_8);

            assertThat(content).isEqualTo(TARGET.toHex() + "\n");
        }

        @Test
        void aNestedNameNestsAsDirectories() {
            refs.createTag("release/v1.0", TARGET);

            assertThat(repositoryRoot.resolve("refs/tags/release/v1.0")).exists();
            assertThat(refs.getTag("release/v1.0")).contains(TARGET);
            assertThat(refs.listTags()).containsExactly("release/v1.0");
        }

        @Test
        void tagsAreListedInNameOrder() {
            refs.createTag("v2", TARGET);
            refs.createTag("v1", TARGET);
            refs.createTag("release/v3", TARGET);

            assertThat(refs.listTags()).containsExactly("release/v3", "v1", "v2");
        }

        @Test
        void aTagSurvivesReopeningTheStore() {
            refs.createTag("v1", TARGET);

            FileSystemRefStore reopened = new FileSystemRefStore(repositoryRoot);

            assertThat(reopened.getTag("v1")).contains(TARGET);
            assertThat(reopened.listTags()).containsExactly("v1");
        }

        @Test
        void aTagMayPointAtAnyObjectNotOnlyACommit() {
            // The store does not know what a target is; an annotated tag points at
            // a tag object, and that must not be rejected here.
            refs.createTag("annotated", OTHER);

            assertThat(refs.getTag("annotated")).contains(OTHER);
        }
    }

    @Nested
    @DisplayName("tags are not branches")
    class SeparateNamespace {

        @Test
        void aTagNeverAppearsInTheBranchList() {
            refs.createBranch("main", TARGET);
            refs.createTag("v1", TARGET);

            assertThat(refs.listBranches()).containsExactly("main");
            assertThat(refs.listTags()).containsExactly("v1");
        }

        @Test
        void aBranchNeverAppearsInTheTagList() {
            refs.createBranch("release/v1.0", TARGET);

            assertThat(refs.listTags()).isEmpty();
        }

        @Test
        void aTagAndABranchMayShareAName() {
            refs.createBranch("v1", TARGET);
            refs.createTag("v1", OTHER);

            assertThat(refs.getBranch("v1")).contains(TARGET);
            assertThat(refs.getTag("v1")).contains(OTHER);
        }

        @Test
        void deletingABranchLeavesATagOfTheSameNameAlone() {
            refs.createBranch("v1", TARGET);
            refs.createTag("v1", OTHER);

            refs.deleteBranch("v1");

            assertThat(refs.getBranch("v1")).isEmpty();
            assertThat(refs.getTag("v1")).contains(OTHER);
        }

        @Test
        void aTagIsNotARemoteTrackingRef() {
            refs.createTag("v1", TARGET);

            assertThat(refs.listRemoteRefs()).isEmpty();
        }
    }

    @Nested
    @DisplayName("immutability")
    class Immutability {

        @Test
        void creatingATagThatExistsIsRefused() {
            refs.createTag("v1", TARGET);

            assertThatThrownBy(() -> refs.createTag("v1", OTHER))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("already exists");
        }

        @Test
        void aRefusedCreationLeavesTheOriginalTargetUntouched() {
            refs.createTag("v1", TARGET);

            assertThatThrownBy(() -> refs.createTag("v1", OTHER))
                    .isInstanceOf(RefException.class);

            assertThat(refs.getTag("v1")).contains(TARGET);
        }

        @Test
        void theStoreOffersNoWayToMoveATag() {
            // The guarantee is structural rather than behavioural: RefStore has
            // updateBranch and setRemoteRef, and deliberately no tag equivalent.
            assertThat(RefStore.class.getMethods())
                    .noneMatch(method -> method.getName().equals("updateTag")
                            || method.getName().equals("setTag")
                            || method.getName().equals("moveTag"));
        }

        @Test
        void aTagMayBeRecreatedAfterDeletion() {
            refs.createTag("v1", TARGET);
            refs.deleteTag("v1");
            refs.createTag("v1", OTHER);

            assertThat(refs.getTag("v1")).contains(OTHER);
        }
    }

    @Nested
    @DisplayName("deletion")
    class Deletion {

        @Test
        void deletingRemovesTheTag() {
            refs.createTag("v1", TARGET);

            assertThat(refs.deleteTag("v1")).isTrue();
            assertThat(refs.getTag("v1")).isEmpty();
            assertThat(refs.listTags()).isEmpty();
        }

        @Test
        void deletingSomethingAbsentReportsFalseRatherThanThrowing() {
            assertThat(refs.deleteTag("never-existed")).isFalse();
        }

        @Test
        void deletingANestedTagPrunesTheEmptyDirectory() {
            refs.createTag("release/v1.0", TARGET);

            refs.deleteTag("release/v1.0");

            assertThat(repositoryRoot.resolve("refs/tags/release")).doesNotExist();
        }

        @Test
        void deletingOneNestedTagKeepsItsSibling() {
            refs.createTag("release/v1.0", TARGET);
            refs.createTag("release/v2.0", OTHER);

            refs.deleteTag("release/v1.0");

            assertThat(refs.listTags()).containsExactly("release/v2.0");
            assertThat(repositoryRoot.resolve("refs/tags/release")).exists();
        }

        @Test
        void deletingATagLeavesTheTagsRootInPlace() {
            refs.createTag("v1", TARGET);

            refs.deleteTag("v1");

            assertThat(repositoryRoot.resolve("refs/tags")).exists();
        }
    }

    @Nested
    @DisplayName("validation and containment")
    class Validation {

        @Test
        void anInvalidNameIsRejectedOnCreation() {
            assertThatThrownBy(() -> refs.createTag("../escape", TARGET))
                    .isInstanceOf(RefException.class);
        }

        @Test
        void anInvalidNameIsRejectedOnLookupToo() {
            // Validated on every use, not only on creation: a name may arrive from
            // a request rather than from something this store wrote.
            assertThatThrownBy(() -> refs.getTag("../../objects/ab/cdef"))
                    .isInstanceOf(RefException.class);
        }

        @Test
        void aNullTargetIsRejected() {
            assertThatThrownBy(() -> refs.createTag("v1", null))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("must point at an object");
        }

        @Test
        void nothingIsWrittenOutsideTheTagsDirectory() {
            assertThatThrownBy(() -> refs.createTag("../heads/main", TARGET))
                    .isInstanceOf(RefException.class);

            assertThat(repositoryRoot.resolve("refs/heads/main")).doesNotExist();
        }

        @Test
        void aDamagedTagFileIsReportedAsSuch() throws Exception {
            refs.createTag("v1", TARGET);
            Files.writeString(repositoryRoot.resolve("refs/tags/v1"), "not an id\n");

            assertThatThrownBy(() -> refs.getTag("v1"))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("valid object id");
        }

        @Test
        void aTemporaryFileIsNotListedAsATag() throws Exception {
            refs.createTag("v1", TARGET);
            Files.writeString(repositoryRoot.resolve("refs/tags/.tmp-ref-inflight"), "x");

            assertThat(refs.listTags()).containsExactly("v1");
        }
    }
}
