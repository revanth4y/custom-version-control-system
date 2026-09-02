package com.gitforge.vcs.ref;

import com.gitforge.vcs.RepositoryFixture;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Signature;
import com.gitforge.vcs.object.Tag;
import com.gitforge.vcs.repository.RepositoryLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;

import static com.gitforge.vcs.RepositoryFixture.files;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tags in revision resolution, and the precedence that governs them.
 *
 * <p>The documented order is:
 *
 * <pre>
 *   HEAD → branch → tag → full object id → abbreviated object id
 * </pre>
 *
 * <p>Every step of it is pinned here, including the case that decides the whole
 * design: a name that is both a branch and a tag. Git resolves tags first; this
 * engine resolves branches first, and a test that did not say so would leave the
 * difference looking accidental.
 */
class TagRevisionResolutionTest {

    @TempDir
    Path tempDir;

    private Path repositoryRoot;
    private RepositoryFixture repository;
    private BranchService branches;
    private TagService tags;
    private ObjectId first;
    private ObjectId second;

    private static final Signature TAGGER = new Signature(
            "Ada Lovelace",
            "ada@example.test",
            Instant.ofEpochSecond(1_700_000_000L),
            ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        repositoryRoot = tempDir.resolve("repo");
        repository = new RepositoryFixture(repositoryRoot, tempDir.resolve("work"));
        branches = repository.branches();
        tags = new TagService(repository.refStore(), repository.objectStore(), new RepositoryLock());

        first = repository.commit("First", null, files("a.txt", "one\n"));
        second = repository.commit("Second", first, files("a.txt", "two\n"));
        branches.createBranch("main", second);
        repository.refStore().setHead(Head.onBranch("main"));
    }

    @Nested
    @DisplayName("a tag resolves")
    class Resolves {

        @Test
        void aLightweightTagResolvesToItsCommit() {
            tags.createLightweight("v1.0.0", first);

            assertThat(branches.resolve("v1.0.0")).contains(first);
        }

        @Test
        void anAnnotatedTagResolvesToTheCommitNotTheTagObject() {
            Tag tag = tags.createAnnotated("v1.0.0", first, TAGGER, "Release\n");

            // The ref holds the tag object's id; resolution peels through it.
            assertThat(repository.refStore().getTag("v1.0.0")).contains(tag.id());
            assertThat(branches.resolve("v1.0.0")).contains(first);
        }

        @Test
        void aChainOfTagsResolvesToTheCommitAtTheEnd() {
            Tag inner = tags.createAnnotated("inner", first, TAGGER, "Inner\n");
            tags.createAnnotated("outer", inner.id(), TAGGER, "Outer\n");

            assertThat(branches.resolve("outer")).contains(first);
        }

        @Test
        void aNestedTagNameResolves() {
            tags.createLightweight("release/v1.0", first);

            assertThat(branches.resolve("release/v1.0")).contains(first);
        }

        @Test
        void anAbsentTagDoesNotResolve() {
            assertThat(branches.resolve("never-tagged")).isEmpty();
        }
    }

    @Nested
    @DisplayName("precedence")
    class Precedence {

        @Test
        void headBeatsEverything() {
            tags.createLightweight("HEAD-ish", first);

            assertThat(branches.resolve("HEAD")).contains(second);
        }

        @Test
        void aBranchBeatsATagOfTheSameName() {
            // The decision this engine makes differently from Git, stated as a test.
            branches.createBranch("shared", second);
            tags.createLightweight("shared", first);

            assertThat(branches.resolve("shared")).contains(second);
            // The tag is still reachable by every other means.
            assertThat(tags.peel("shared")).contains(first);
        }

        @Test
        void deletingTheBranchLetsTheTagOfTheSameNameResolve() {
            branches.createBranch("shared", second);
            tags.createLightweight("shared", first);

            branches.deleteBranch("shared");

            assertThat(branches.resolve("shared")).contains(first);
        }

        @Test
        void aTagBeatsAnAbbreviatedObjectId() {
            // A tag named as a prefix of another object's id is still a name the
            // user created, and names come before ids.
            String prefix = second.toHex().substring(0, 8);
            tags.createLightweight(prefix, first);

            assertThat(branches.resolve(prefix)).contains(first);
        }

        @Test
        void aFullObjectIdStillResolvesWhenNoNameMatches() {
            tags.createLightweight("v1", first);

            assertThat(branches.resolve(second.toHex())).contains(second);
        }

        @Test
        void anAbbreviationStillResolvesWhenNoNameMatches() {
            tags.createLightweight("v1", first);

            assertThat(branches.resolve(second.toHex().substring(0, 10))).contains(second);
        }
    }

    @Nested
    @DisplayName("relative expressions")
    class Relative {

        @Test
        void aTagMayCarryARelativeSuffix() {
            tags.createLightweight("v1.0.0", second);

            assertThat(branches.resolve("v1.0.0~1")).contains(first);
            assertThat(branches.resolve("v1.0.0^1")).contains(first);
        }

        @Test
        void anAnnotatedTagMayCarryARelativeSuffixToo() {
            tags.createAnnotated("v1.0.0", second, TAGGER, "Release\n");

            assertThat(branches.resolve("v1.0.0~1")).contains(first);
        }
    }

    @Nested
    @DisplayName("tags cannot become HEAD")
    class HeadConstraint {

        @Test
        void aSymbolicHeadPointingIntoRefsTagsIsStillRejected() throws Exception {
            tags.createLightweight("v1.0.0", first);
            Files.writeString(repositoryRoot.resolve("HEAD"), "ref: refs/tags/v1.0.0\n");

            // The pre-existing constraint, unchanged by tags becoming real.
            assertThatThrownBy(() -> repository.refStore().readHead())
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("outside");
        }

        @Test
        void setHeadCannotBeTalkedIntoNamingATag() {
            tags.createLightweight("v1.0.0", first);

            // Head.onBranch writes refs/heads/<name>, so the only way a tag could
            // become HEAD is through a name containing a traversal, which the
            // branch rules refuse.
            assertThatThrownBy(() ->
                    repository.refStore().setHead(Head.onBranch("../tags/v1.0.0")))
                    .isInstanceOf(RefException.class);
        }
    }

    @Nested
    @DisplayName("resolution is used by the operations built on it")
    class DownstreamUse {

        @Test
        void aBranchMayBeCreatedFromATag() {
            tags.createAnnotated("v1.0.0", first, TAGGER, "Release\n");

            branches.createBranchFrom("hotfix", "v1.0.0");

            // Peeled: the branch names the commit, never the tag object.
            assertThat(branches.getBranch("hotfix")).contains(first);
        }
    }
}
