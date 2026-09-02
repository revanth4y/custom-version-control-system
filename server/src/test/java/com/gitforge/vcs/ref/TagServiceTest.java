package com.gitforge.vcs.ref;

import com.gitforge.vcs.RepositoryFixture;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.ObjectType;
import com.gitforge.vcs.object.Signature;
import com.gitforge.vcs.object.Tag;
import com.gitforge.vcs.repository.RepositoryLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;

import static com.gitforge.vcs.RepositoryFixture.files;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rules layered over tag storage: what may be tagged, what may not be moved,
 * and how a chain of tags is followed down to the thing it ultimately names.
 */
class TagServiceTest {

    @TempDir
    Path tempDir;

    private RepositoryFixture repository;
    private TagService tags;
    private ObjectId commit;

    private static final Signature TAGGER = new Signature(
            "Ada Lovelace",
            "ada@example.test",
            Instant.ofEpochSecond(1_700_000_000L),
            ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        repository = new RepositoryFixture(tempDir.resolve("repo"), tempDir.resolve("work"));
        tags = new TagService(repository.refStore(), repository.objectStore(), new RepositoryLock());
        commit = repository.commit("Initial", null, files("README.md", "# Demo\n"));
    }

    @Nested
    @DisplayName("lightweight tags")
    class Lightweight {

        @Test
        void nameACommitDirectly() {
            tags.createLightweight("v1.0.0", commit);

            assertThat(tags.getTag("v1.0.0")).contains(commit);
            assertThat(tags.peel("v1.0.0")).contains(commit);
            assertThat(tags.listTags()).containsExactly("v1.0.0");
        }

        @Test
        void writeNoObject() {
            long before = repository.objectStore().count();

            tags.createLightweight("v1.0.0", commit);

            assertThat(repository.objectStore().count()).isEqualTo(before);
        }

        @Test
        void haveNoAnnotation() {
            tags.createLightweight("v1.0.0", commit);

            assertThat(tags.annotationOf("v1.0.0")).isEmpty();
        }

        @Test
        void mayNameATreeOrABlobAsWellAsACommit() {
            ObjectId tree = repository.objectStore().readCommit(commit).tree();

            tags.createLightweight("the-tree", tree);

            assertThat(tags.peel("the-tree")).contains(tree);
        }
    }

    @Nested
    @DisplayName("annotated tags")
    class Annotated {

        @Test
        void writeATagObjectAndPointTheRefAtIt() {
            Tag tag = tags.createAnnotated("v1.0.0", commit, TAGGER, "Release 1.0\n");

            assertThat(tags.getTag("v1.0.0")).contains(tag.id());
            assertThat(tags.getTag("v1.0.0").orElseThrow()).isNotEqualTo(commit);
            assertThat(repository.objectStore().contains(tag.id())).isTrue();
        }

        @Test
        void peelDownToTheCommit() {
            tags.createAnnotated("v1.0.0", commit, TAGGER, "Release 1.0\n");

            assertThat(tags.peel("v1.0.0")).contains(commit);
        }

        @Test
        void recordTheTargetsType() {
            Tag tag = tags.createAnnotated("v1.0.0", commit, TAGGER, "Release\n");

            assertThat(tag.targetType()).isEqualTo(ObjectType.COMMIT);
        }

        @Test
        void carryTheirMessageAndTagger() {
            tags.createAnnotated("v1.0.0", commit, TAGGER, "Release 1.0\n");

            Tag annotation = tags.annotationOf("v1.0.0").orElseThrow();

            assertThat(annotation.message()).isEqualTo("Release 1.0\n");
            assertThat(annotation.tagger().name()).isEqualTo("Ada Lovelace");
            assertThat(annotation.name()).isEqualTo("v1.0.0");
        }

        @Test
        void needAMessage() {
            assertThatThrownBy(() -> tags.createAnnotated("v1", commit, TAGGER, "  "))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("needs a message");
        }

        @Test
        void needATagger() {
            assertThatThrownBy(() -> tags.createAnnotated("v1", commit, null, "m"))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("needs a tagger");
        }

        @Test
        void leaveNothingBehindWhenTheNameIsAlreadyTaken() {
            tags.createAnnotated("v1.0.0", commit, TAGGER, "First\n");
            long after = repository.objectStore().count();

            assertThatThrownBy(() -> tags.createAnnotated("v1.0.0", commit, TAGGER, "Second\n"))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("already exists");

            // The duplicate is refused before the object is written, so a rejected
            // creation does not leave an orphan tag object in the store.
            assertThat(repository.objectStore().count()).isEqualTo(after);
        }
    }

    @Nested
    @DisplayName("peeling")
    class Peeling {

        @Test
        void aLightweightTagPeelsToItself() {
            tags.createLightweight("v1", commit);

            assertThat(tags.peel("v1")).contains(commit);
        }

        @Test
        void aChainIsFollowedToTheEnd() {
            Tag inner = tags.createAnnotated("inner", commit, TAGGER, "Inner\n");
            Tag middle = tags.createAnnotated("middle", inner.id(), TAGGER, "Middle\n");
            tags.createAnnotated("outer", middle.id(), TAGGER, "Outer\n");

            assertThat(tags.peel("outer")).contains(commit);
        }

        @Test
        void theChainIsReportedInOrder() {
            Tag inner = tags.createAnnotated("inner", commit, TAGGER, "Inner\n");
            Tag outer = tags.createAnnotated("outer", inner.id(), TAGGER, "Outer\n");

            assertThat(tags.chainOf("outer"))
                    .containsExactly(outer.id(), inner.id(), commit);
        }

        @Test
        void aLightweightChainIsJustTheTarget() {
            tags.createLightweight("v1", commit);

            assertThat(tags.chainOf("v1")).containsExactly(commit);
        }

        @Test
        void anAbsentTagPeelsToNothing() {
            assertThat(tags.peel("never-existed")).isEmpty();
            assertThat(tags.chainOf("never-existed")).isEmpty();
        }

        @Test
        void aChainDeeperThanTheCeilingIsRefused() {
            ObjectId current = commit;
            for (int depth = 0; depth <= TagService.MAX_PEEL_DEPTH + 1; depth++) {
                Tag tag = tags.createAnnotated("t" + depth, current, TAGGER, "Link " + depth + "\n");
                current = tag.id();
            }

            assertThatThrownBy(() -> tags.peel("t" + (TagService.MAX_PEEL_DEPTH + 1)))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("deeper than");
        }
    }

    @Nested
    @DisplayName("immutability")
    class Immutability {

        @Test
        void aTagCannotBeCreatedTwice() {
            tags.createLightweight("v1", commit);

            assertThatThrownBy(() -> tags.createLightweight("v1", commit))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("already exists");
        }

        @Test
        void anAnnotatedTagCannotReplaceALightweightOne() {
            tags.createLightweight("v1", commit);

            assertThatThrownBy(() -> tags.createAnnotated("v1", commit, TAGGER, "m"))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("already exists");

            assertThat(tags.getTag("v1")).contains(commit);
        }

        @Test
        void movingRequiresTwoDeliberateActs() {
            ObjectId second = repository.commit("Second", commit, files("a.txt", "a\n"));
            tags.createLightweight("v1", commit);

            assertThat(tags.deleteTag("v1")).isTrue();
            tags.createLightweight("v1", second);

            assertThat(tags.getTag("v1")).contains(second);
        }
    }

    @Nested
    @DisplayName("the target must exist")
    class TargetValidation {

        @Test
        void aLightweightTagCannotNameAnAbsentObject() {
            ObjectId absent = ObjectId.fromHex("da39a3ee5e6b4b0d3255bfef95601890afd80709");

            assertThatThrownBy(() -> tags.createLightweight("v1", absent))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("does not exist");

            assertThat(tags.listTags()).isEmpty();
        }

        @Test
        void anAnnotatedTagCannotNameAnAbsentObject() {
            ObjectId absent = ObjectId.fromHex("da39a3ee5e6b4b0d3255bfef95601890afd80709");

            assertThatThrownBy(() -> tags.createAnnotated("v1", absent, TAGGER, "m"))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("does not exist");
        }

        @Test
        void aNullTargetIsRejected() {
            assertThatThrownBy(() -> tags.createLightweight("v1", null))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("must point at an object");
        }

        @Test
        void anInvalidNameIsRejectedBeforeAnythingIsWritten() {
            long before = repository.objectStore().count();

            assertThatThrownBy(() -> tags.createAnnotated("../escape", commit, TAGGER, "m"))
                    .isInstanceOf(RefException.class);

            assertThat(repository.objectStore().count()).isEqualTo(before);
            assertThat(tags.listTags()).isEmpty();
        }
    }

    @Nested
    @DisplayName("deletion")
    class Deletion {

        @Test
        void removesTheRefAndNoObject() {
            Tag tag = tags.createAnnotated("v1", commit, TAGGER, "Release\n");

            assertThat(tags.deleteTag("v1")).isTrue();

            assertThat(tags.getTag("v1")).isEmpty();
            // The tag object and the commit are both still stored: deletion drops a
            // reference, and reclaiming storage is a separate thing somebody asks for.
            assertThat(repository.objectStore().contains(tag.id())).isTrue();
            assertThat(repository.objectStore().contains(commit)).isTrue();
        }

        @Test
        void deletingSomethingAbsentIsFalseRatherThanAnError() {
            assertThat(tags.deleteTag("never-existed")).isFalse();
        }
    }
}
