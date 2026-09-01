package com.gitforge.vcs.ref;

import com.gitforge.vcs.RepositoryFixture;
import com.gitforge.vcs.object.AmbiguousObjectIdException;
import com.gitforge.vcs.object.Blob;
import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Revisions written relative to another commit.
 *
 * <p>Kept apart from {@link BranchServiceTest}, which pins the four forms that
 * name a commit outright. Those must go on holding exactly as they did, so
 * nothing here touches them.
 */
class RelativeRevisionTest {

    @TempDir
    Path tempDir;

    private RepositoryFixture repository;
    private BranchService branches;

    /** A straight line: first is the root, fourth is the tip of main. */
    private ObjectId first;
    private ObjectId second;
    private ObjectId third;
    private ObjectId fourth;

    @BeforeEach
    void setUp() {
        repository = new RepositoryFixture(tempDir.resolve("repo"), tempDir.resolve("work"));
        branches = repository.branches();

        first = repository.commit("First", null, "a.txt", "one\n");
        second = repository.commit("Second", first, "a.txt", "two\n");
        third = repository.commit("Third", second, "a.txt", "three\n");
        fourth = repository.commit("Fourth", third, "a.txt", "four\n");
        branches.createBranch("main", fourth);
    }

    private ObjectId resolved(String revision) {
        Optional<ObjectId> found = branches.resolve(revision);
        assertThat(found).as("revision %s", revision).isPresent();
        return found.get();
    }

    /** A commit with two parents, which is the only way to reach {@code ^2}. */
    private ObjectId mergeOf(ObjectId ours, ObjectId theirs) {
        Signature who = Signature.of("Tester", "tester@example.test", Instant.now());
        Commit merge = new Commit(
                repository.objectStore().readCommit(ours).tree(),
                List.of(ours, theirs),
                who,
                who,
                "Merge");
        return repository.objectStore().write(merge);
    }

    @Nested
    @DisplayName("walking back")
    class WalkingBack {

        @Test
        void aCaretIsTheFirstParent() {
            assertThat(resolved("main^")).isEqualTo(third);
            assertThat(resolved("HEAD^")).isEqualTo(third);
        }

        @Test
        void caretOneIsTheSameAsCaret() {
            assertThat(resolved("main^1")).isEqualTo(resolved("main^"));
        }

        @Test
        void aTildeIsOneGenerationBack() {
            assertThat(resolved("main~")).isEqualTo(third);
            assertThat(resolved("main~1")).isEqualTo(third);
        }

        @Test
        void aTildeCountsGenerations() {
            assertThat(resolved("main~2")).isEqualTo(second);
            assertThat(resolved("main~3")).isEqualTo(first);
        }

        @Test
        void zeroIsTheCommitItself() {
            assertThat(resolved("main~0")).isEqualTo(fourth);
            assertThat(resolved("main^0")).isEqualTo(fourth);
            assertThat(resolved("HEAD~0")).isEqualTo(fourth);
        }

        @Test
        void stepsChainLeftToRight() {
            assertThat(resolved("main~1~1")).isEqualTo(second);
            assertThat(resolved("main^^")).isEqualTo(second);
            assertThat(resolved("main~1^1")).isEqualTo(second);
            assertThat(resolved("main^~2")).isEqualTo(first);
        }

        @Test
        void everyBaseFormAcceptsASuffix() {
            assertThat(resolved("HEAD~1")).isEqualTo(third);
            assertThat(resolved("main~1")).isEqualTo(third);
            assertThat(resolved(fourth.toHex() + "~1")).isEqualTo(third);
            assertThat(resolved(fourth.toHex().substring(0, 8) + "~1")).isEqualTo(third);
        }
    }

    @Nested
    @DisplayName("parents by position")
    class Parents {

        @Test
        void caretTwoIsTheSecondParentOfAMerge() {
            ObjectId sideBranch = repository.commit("Side", second, "b.txt", "side\n");
            ObjectId merge = mergeOf(fourth, sideBranch);
            branches.createBranch("merged", merge);

            assertThat(resolved("merged^1")).isEqualTo(fourth);
            assertThat(resolved("merged^2")).isEqualTo(sideBranch);
        }

        @Test
        void aTildeAlwaysFollowsTheFirstParent() {
            ObjectId sideBranch = repository.commit("Side", second, "b.txt", "side\n");
            ObjectId merge = mergeOf(fourth, sideBranch);
            branches.createBranch("merged", merge);

            // Not the side branch: ~1 is the first parent, whatever else exists.
            assertThat(resolved("merged~1")).isEqualTo(fourth);
        }

        @Test
        void caretTwoOnASingleParentCommitIsNotFound() {
            // The whole point of the invariant: never quietly answer ^1 instead.
            assertThat(branches.resolve("main^2")).isEmpty();
        }

        @Test
        void aParentBeyondWhatACommitHasIsNotFound() {
            assertThat(branches.resolve("main^3")).isEmpty();
            assertThat(branches.resolve("main^9")).isEmpty();
        }

        @Test
        void aCaretOnTheRootCommitIsNotFound() {
            assertThat(branches.resolve("main~3^")).isEmpty();
        }
    }

    @Nested
    @DisplayName("the beginning of history")
    class Root {

        @Test
        void walkingPastTheRootIsNotFoundRatherThanAFailure() {
            assertThat(branches.resolve("main~4")).isEmpty();
            assertThat(branches.resolve("main~5")).isEmpty();
            assertThat(branches.resolve("main~99")).isEmpty();
        }

        @Test
        void theRootItselfStillResolves() {
            assertThat(resolved("main~3")).isEqualTo(first);
        }
    }

    @Nested
    @DisplayName("malformed against merely absent")
    class Malformed {

        @Test
        void anUnreadableExpressionIsRefused() {
            // Nothing could answer these, so they are a bad question rather than
            // a question with no answer.
            assertThatThrownBy(() -> branches.resolve("main~abc"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> branches.resolve("main^x"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> branches.resolve("~1"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> branches.resolve("^"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void anUnknownBaseWithAValidSuffixIsMerelyAbsent() {
            // The expression reads perfectly well; the thing it names is not here.
            assertThat(branches.resolve("nosuchbranch~1")).isEmpty();
            assertThat(branches.resolve("nosuchbranch^2")).isEmpty();
        }

        @Test
        void aCountTooLargeToMeanAnythingIsRefused() {
            assertThatThrownBy(() -> branches.resolve("main~999999999999999999999"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> branches.resolve("main~" + (RevisionSuffix.MAX_COUNT + 1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void aChainLongerThanTheBudgetIsRefused() {
            assertThatThrownBy(() -> branches.resolve("main" + "^".repeat(RevisionSuffix.MAX_STEPS + 1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void theLargestPermittedCountIsStillRead() {
            // At the bound, not past it: read, walked, and past the root.
            assertThat(branches.resolve("main~" + RevisionSuffix.MAX_COUNT)).isEmpty();
        }
    }

    @Nested
    @DisplayName("names come first")
    class Precedence {

        @Test
        void theWholeStringIsTriedAsANameBeforeAnySuffixIsParsed() {
            // Ordering, stated as a property: whatever a revision looks like,
            // if the entire string names a commit that is the answer. Only a
            // string that names nothing is read as an expression.
            assertThat(resolved("main")).isEqualTo(fourth);
            assertThat(resolved(fourth.toHex())).isEqualTo(fourth);
            assertThat(resolved("main~1")).isEqualTo(third);
        }

        @Test
        void aRefNamedWithASuffixCharacterCannotBeReachedByNameAtAll() {
            // Not a policy choice made here. BranchName forbids ~ and ^, and
            // FileSystemRefStore validates on every lookup as well as on
            // creation, so such a ref is unreadable by name however it got onto
            // disk. Recorded because it is what makes the precedence question
            // moot rather than merely handled.
            writeRefDirectly("odd^2", second);

            // Lookup itself refuses the name, so the file is unreachable.
            assertThatThrownBy(() -> branches.getBranch("odd^2"))
                    .isInstanceOf(RefException.class);
            // resolve swallows that and reads the string as an expression, whose
            // base "odd" names nothing: absent, not the commit on disk.
            assertThat(branches.resolve("odd^2")).isEmpty();
        }

        @Test
        void anOrdinaryBranchIsUnaffected() {
            assertThat(resolved("main")).isEqualTo(fourth);
            assertThat(resolved("HEAD")).isEqualTo(fourth);
        }
    }

    @Nested
    @DisplayName("alongside the existing forms")
    class ExistingForms {

        @Test
        void abbreviationsStillResolveWithAndWithoutASuffix() {
            String abbreviated = fourth.toHex().substring(0, 6);

            assertThat(resolved(abbreviated)).isEqualTo(fourth);
            assertThat(resolved(abbreviated + "^")).isEqualTo(third);
        }

        @Test
        void anAmbiguousAbbreviationIsStillReportedAsAmbiguous() {
            String prefix = writeCollidingObjects();

            assertThatThrownBy(() -> branches.resolve(prefix))
                    .isInstanceOf(AmbiguousObjectIdException.class);
            assertThatThrownBy(() -> branches.resolve(prefix + "~1"))
                    .isInstanceOf(AmbiguousObjectIdException.class);
        }

        @Test
        void aFullIdThatIsNotStoredIsStillAbsent() {
            String absent = "0".repeat(40);

            assertThat(branches.resolve(absent)).isEmpty();
            assertThat(branches.resolve(absent + "~1")).isEmpty();
        }

        @Test
        void blankAndNullAreStillEmptyRatherThanMalformed() {
            assertThat(branches.resolve(null)).isEmpty();
            assertThat(branches.resolve("")).isEmpty();
            assertThat(branches.resolve("   ")).isEmpty();
        }

        @Test
        void aRevisionWithNoSuffixCharacterIsNeverMalformed() {
            // Unknown names stay not-found; only an unreadable expression is refused.
            assertThat(branches.resolve("no-such-thing")).isEmpty();
            assertThat(branches.resolve("zz")).isEmpty();
        }
    }

    /**
     * Writes a reference file whose name no API would accept.
     *
     * <p>Both {@link BranchName} and the ref store reject {@code ~} and
     * {@code ^}, so a ref containing one cannot be created through this
     * codebase. It can still exist as a file, and the rule that a name beats an
     * expression should not depend on the assumption that nothing ever put one
     * there. Written directly, inside this test's own temporary repository.
     */
    private void writeRefDirectly(String name, ObjectId target) {
        try {
            Path ref = tempDir.resolve("repo").resolve("refs").resolve("heads").resolve(name);
            java.nio.file.Files.createDirectories(ref.getParent());
            java.nio.file.Files.writeString(ref, target.toHex() + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Could not write the reference", ex);
        }
    }

    /** Writes blobs until two share a four-character prefix, and returns it. */
    private String writeCollidingObjects() {
        java.util.Map<String, ObjectId> seen = new java.util.HashMap<>();
        for (int i = 0; i < 1_000_000; i++) {
            Blob candidate = new Blob(("collide-" + i).getBytes(StandardCharsets.UTF_8));
            String prefix = candidate.id().toHex().substring(0, 4);
            if (seen.containsKey(prefix)) {
                repository.objectStore().write(candidate);
                repository.objectStore().write(new Blob(("collide-" + seen.get(prefix)).getBytes(StandardCharsets.UTF_8)));
                return prefix;
            }
            seen.put(prefix, repository.objectStore().write(candidate));
        }
        throw new IllegalStateException("No colliding prefix found");
    }
}
