package com.gitforge.vcs.repository;

import com.gitforge.vcs.ExplodingObjectStore;
import com.gitforge.vcs.merge.ConflictKind;
import com.gitforge.vcs.merge.MergeConflict;
import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.ObjectType;
import com.gitforge.vcs.object.Signature;
import com.gitforge.vcs.ref.FileSystemRefStore;
import com.gitforge.vcs.ref.Head;
import com.gitforge.vcs.ref.RefException;
import com.gitforge.vcs.ref.RefStore;
import com.gitforge.vcs.storage.FileSystemObjectStore;
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

class MergeOrchestratorTest {

    @TempDir
    Path storageRoot;

    private VcsRepository repository;
    private int sequence;

    @BeforeEach
    void setUp() {
        repository = new VcsRepositoryFactory(storageRoot).initialise(RepositoryId.of("demo"), "main");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private Signature signature() {
        return Signature.of("Ada", "ada@example.com", Instant.ofEpochSecond(1_700_000_000L + sequence++));
    }

    private ObjectId commit(String branch, String message, FileChange... changes) {
        return repository.commits().commit(branch, List.of(changes), signature(), message);
    }

    private MergeOutcome merge(String ours, String theirs) {
        return repository.merges().merge(ours, theirs, signature(), null);
    }

    private String read(String revision, String path) {
        return new String(repository.reader().readFile(revision, path).orElseThrow(), StandardCharsets.UTF_8);
    }

    /** main and feature diverge: each edits a different file. */
    private void divergentBranches() {
        commit("main", "Initial commit",
                FileChange.put("a.txt", bytes("a\n")),
                FileChange.put("b.txt", bytes("b\n")));
        repository.branches().createBranchFrom("feature", "main");

        commit("main", "Edit a on main", FileChange.put("a.txt", bytes("main edited a\n")));
        commit("feature", "Edit b on feature", FileChange.put("b.txt", bytes("feature edited b\n")));
    }

    @Nested
    @DisplayName("already up to date")
    class UpToDate {

        @Test
        void mergingABranchIntoItself() {
            commit("main", "Initial commit", FileChange.put("a.txt", bytes("a\n")));
            repository.branches().createBranchFrom("copy", "main");

            MergeOutcome outcome = merge("main", "copy");

            assertThat(outcome).isInstanceOf(MergeOutcome.AlreadyUpToDate.class);
            assertThat(outcome.isSuccessful()).isTrue();
        }

        @Test
        void whenTheirBranchIsAlreadyContainedInOurs() {
            ObjectId base = commit("main", "Initial commit", FileChange.put("a.txt", bytes("a\n")));
            repository.branches().createBranch("behind", base);
            ObjectId ahead = commit("main", "Move ahead", FileChange.put("b.txt", bytes("b\n")));

            MergeOutcome outcome = merge("main", "behind");

            assertThat(outcome).isEqualTo(new MergeOutcome.AlreadyUpToDate(ahead));
            // Nothing moved and nothing was created.
            assertThat(repository.branches().getBranch("main")).contains(ahead);
        }

        @Test
        void noCommitIsCreated() {
            ObjectId base = commit("main", "Initial commit", FileChange.put("a.txt", bytes("a\n")));
            repository.branches().createBranch("behind", base);
            commit("main", "Move ahead", FileChange.put("b.txt", bytes("b\n")));
            long objectsBefore = repository.objects().count();

            merge("main", "behind");

            assertThat(repository.objects().count()).isEqualTo(objectsBefore);
        }
    }

    @Nested
    @DisplayName("fast-forward")
    class FastForward {

        @Test
        void movesOurBranchWhenItIsStrictlyBehind() {
            ObjectId base = commit("main", "Initial commit", FileChange.put("a.txt", bytes("a\n")));
            repository.branches().createBranch("feature", base);
            ObjectId ahead = commit("feature", "Feature work", FileChange.put("b.txt", bytes("b\n")));

            MergeOutcome outcome = merge("main", "feature");

            assertThat(outcome).isEqualTo(new MergeOutcome.FastForwarded(ahead));
            assertThat(repository.branches().getBranch("main")).contains(ahead);
        }

        @Test
        void createsNoMergeCommit() {
            ObjectId base = commit("main", "Initial commit", FileChange.put("a.txt", bytes("a\n")));
            repository.branches().createBranch("feature", base);
            ObjectId ahead = commit("feature", "Feature work", FileChange.put("b.txt", bytes("b\n")));

            merge("main", "feature");

            // The tip is theirs, unchanged — not a new commit wrapping it.
            Commit tip = repository.objects().readCommit(
                    repository.branches().getBranch("main").orElseThrow());
            assertThat(tip.id()).isEqualTo(ahead);
            assertThat(tip.isMerge()).isFalse();
        }

        @Test
        void bringsTheirFilesOntoOurBranch() {
            ObjectId base = commit("main", "Initial commit", FileChange.put("a.txt", bytes("a\n")));
            repository.branches().createBranch("feature", base);
            commit("feature", "Feature work", FileChange.put("b.txt", bytes("b\n")));

            merge("main", "feature");

            assertThat(read("main", "b.txt")).isEqualTo("b\n");
        }

        @Test
        void leavesTheirBranchAlone() {
            ObjectId base = commit("main", "Initial commit", FileChange.put("a.txt", bytes("a\n")));
            repository.branches().createBranch("feature", base);
            ObjectId theirTip = commit("feature", "Feature work", FileChange.put("b.txt", bytes("b\n")));

            merge("main", "feature");

            assertThat(repository.branches().getBranch("feature")).contains(theirTip);
        }
    }

    @Nested
    @DisplayName("three-way merge")
    class ThreeWay {

        @Test
        void combinesChangesFromBothBranches() {
            divergentBranches();

            MergeOutcome outcome = merge("main", "feature");

            assertThat(outcome).isInstanceOf(MergeOutcome.Merged.class);
            assertThat(read("main", "a.txt")).isEqualTo("main edited a\n");
            assertThat(read("main", "b.txt")).isEqualTo("feature edited b\n");
        }

        @Test
        void recordsAMergeCommitWithBothParentsInOrder() {
            divergentBranches();
            ObjectId ours = repository.branches().getBranch("main").orElseThrow();
            ObjectId theirs = repository.branches().getBranch("feature").orElseThrow();

            MergeOutcome.Merged merged = (MergeOutcome.Merged) merge("main", "feature");
            Commit mergeCommit = repository.objects().readCommit(merged.mergeCommit());

            assertThat(mergeCommit.isMerge()).isTrue();
            // Order is identity: parent 0 is the branch merged into.
            assertThat(mergeCommit.parents()).containsExactly(ours, theirs);
            assertThat(mergeCommit.parents().getFirst()).isEqualTo(ours);
            assertThat(mergeCommit.parents().get(1)).isEqualTo(theirs);
        }

        @Test
        void theMergeCommitReferencesTheMergedTree() {
            divergentBranches();

            MergeOutcome.Merged merged = (MergeOutcome.Merged) merge("main", "feature");

            assertThat(repository.objects().readCommit(merged.mergeCommit()).tree())
                    .isEqualTo(merged.tree());
        }

        @Test
        void theMergeCommitIsContentAddressedLikeAnyOther() {
            divergentBranches();

            MergeOutcome.Merged merged = (MergeOutcome.Merged) merge("main", "feature");

            assertThat(repository.objects().readCommit(merged.mergeCommit()).id())
                    .isEqualTo(merged.mergeCommit());
            repository.objects().verify(merged.mergeCommit());
        }

        @Test
        void recordsAuthorCommitterAndMessage() {
            divergentBranches();
            Signature author = Signature.of("Ada", "ada@example.com", Instant.ofEpochSecond(1_700_009_000L));

            MergeOutcome.Merged merged = (MergeOutcome.Merged) repository.merges()
                    .merge("main", "feature", author, "Custom merge message");

            Commit commit = repository.objects().readCommit(merged.mergeCommit());
            assertThat(commit.author()).isEqualTo(author);
            assertThat(commit.committer()).isEqualTo(author);
            assertThat(commit.message()).isEqualTo("Custom merge message\n");
        }

        @Test
        void suppliesADefaultMessageWhenNoneIsGiven() {
            divergentBranches();

            MergeOutcome.Merged merged = (MergeOutcome.Merged) merge("main", "feature");

            assertThat(repository.objects().readCommit(merged.mergeCommit()).message())
                    .isEqualTo("Merge branch 'feature' into main\n");
        }

        @Test
        void advancesOurBranchAndLeavesTheirsAlone() {
            divergentBranches();
            ObjectId theirTip = repository.branches().getBranch("feature").orElseThrow();

            MergeOutcome.Merged merged = (MergeOutcome.Merged) merge("main", "feature");

            assertThat(repository.branches().getBranch("main")).contains(merged.mergeCommit());
            assertThat(repository.branches().getBranch("feature")).contains(theirTip);
        }

        @Test
        void bothBranchesBecomeAncestorsOfTheMerge() {
            divergentBranches();
            ObjectId ours = repository.branches().getBranch("main").orElseThrow();
            ObjectId theirs = repository.branches().getBranch("feature").orElseThrow();

            MergeOutcome.Merged merged = (MergeOutcome.Merged) merge("main", "feature");

            var graph = new com.gitforge.vcs.graph.CommitGraph(repository.objects());
            assertThat(graph.isAncestor(ours, merged.mergeCommit())).isTrue();
            assertThat(graph.isAncestor(theirs, merged.mergeCommit())).isTrue();
        }

        @Test
        void mergingUnrelatedHistoriesCombinesThem() {
            commit("main", "Main history", FileChange.put("main.txt", bytes("main\n")));
            // A branch with no shared ancestry at all.
            ObjectId orphanTree = repository.commits().commit(
                    "orphan", List.of(FileChange.put("orphan.txt", bytes("orphan\n"))), signature(), "Orphan");
            assertThat(orphanTree).isNotNull();

            MergeOutcome outcome = merge("main", "orphan");

            assertThat(outcome).isInstanceOf(MergeOutcome.Merged.class);
            assertThat(read("main", "main.txt")).isEqualTo("main\n");
            assertThat(read("main", "orphan.txt")).isEqualTo("orphan\n");
        }

        @Test
        void repeatingAMergeProducesIdenticalIds() {
            divergentBranches();
            Signature fixed = Signature.of("Ada", "ada@example.com", Instant.ofEpochSecond(1_700_009_999L));

            MergeOutcome.Merged first = (MergeOutcome.Merged) repository.merges()
                    .merge("main", "feature", fixed, "Same message");

            // Reset main and merge again with identical inputs.
            repository.branches().updateBranch("main", repository.objects()
                    .readCommit(first.mergeCommit()).parents().getFirst());
            MergeOutcome.Merged second = (MergeOutcome.Merged) repository.merges()
                    .merge("main", "feature", fixed, "Same message");

            assertThat(second.tree()).isEqualTo(first.tree());
            assertThat(second.mergeCommit()).isEqualTo(first.mergeCommit());
        }
    }

    @Nested
    @DisplayName("conflicts")
    class Conflicts {

        /** Both branches edit the same file differently. */
        private void conflictingBranches() {
            commit("main", "Initial commit", FileChange.put("a.txt", bytes("base\n")));
            repository.branches().createBranchFrom("feature", "main");

            commit("main", "Main edit", FileChange.put("a.txt", bytes("main version\n")));
            commit("feature", "Feature edit", FileChange.put("a.txt", bytes("feature version\n")));
        }

        @Test
        void areReportedRatherThanResolved() {
            conflictingBranches();

            MergeOutcome outcome = merge("main", "feature");

            assertThat(outcome).isInstanceOf(MergeOutcome.Conflicted.class);
            assertThat(outcome.isSuccessful()).isFalse();

            List<MergeConflict> conflicts = ((MergeOutcome.Conflicted) outcome).conflicts();
            assertThat(conflicts).singleElement()
                    .satisfies(conflict -> {
                        assertThat(conflict.path()).isEqualTo("a.txt");
                        assertThat(conflict.kind()).isEqualTo(ConflictKind.CONTENT);
                    });
        }

        @Test
        void leaveTheBranchExactlyWhereItWas() {
            conflictingBranches();
            ObjectId before = repository.branches().getBranch("main").orElseThrow();

            merge("main", "feature");

            assertThat(repository.branches().getBranch("main")).contains(before);
            assertThat(read("main", "a.txt")).isEqualTo("main version\n");
        }

        @Test
        void createNoMergeCommitAndWriteNothing() {
            conflictingBranches();
            long objectsBefore = repository.objects().count();

            merge("main", "feature");

            assertThat(repository.objects().count()).isEqualTo(objectsBefore);
        }

        @Test
        void leaveTheirBranchAloneToo() {
            conflictingBranches();
            ObjectId theirsBefore = repository.branches().getBranch("feature").orElseThrow();

            merge("main", "feature");

            assertThat(repository.branches().getBranch("feature")).contains(theirsBefore);
        }

        @Test
        void reportWhatWouldHaveMergedCleanly() {
            commit("main", "Initial commit",
                    FileChange.put("a.txt", bytes("base\n")),
                    FileChange.put("b.txt", bytes("base\n")));
            repository.branches().createBranchFrom("feature", "main");

            commit("main", "Main edit", FileChange.put("a.txt", bytes("main version\n")));
            commit("feature", "Feature edits",
                    FileChange.put("a.txt", bytes("feature version\n")),
                    FileChange.put("b.txt", bytes("feature only\n")));

            MergeOutcome.Conflicted outcome = (MergeOutcome.Conflicted) merge("main", "feature");

            assertThat(outcome.conflicts()).extracting(MergeConflict::path).containsExactly("a.txt");
            assertThat(outcome.cleanlyMerged()).extracting(change -> change.path()).containsExactly("b.txt");
        }

        @Test
        void aRetryAfterResolvingSucceeds() {
            conflictingBranches();
            assertThat(merge("main", "feature")).isInstanceOf(MergeOutcome.Conflicted.class);

            // Resolve by adopting their content on our branch, so both sides
            // now agree on the file.
            commit("main", "Adopt feature version", FileChange.put("a.txt", bytes("feature version\n")));

            assertThat(merge("main", "feature")).isInstanceOf(MergeOutcome.Merged.class);
        }
    }

    @Nested
    @DisplayName("failure safety")
    class FailureSafety {

        @Test
        void aFailureWritingTheMergeCommitLeavesTheBranchWhereItWas() {
            Path root = storageRoot.resolve("boom");
            ExplodingObjectStore store = new ExplodingObjectStore(new FileSystemObjectStore(root));
            RefStore refs = new FileSystemRefStore(root);
            refs.setHead(Head.onBranch("main"));
            VcsRepository boom = new VcsRepository(RepositoryId.of("boom"), store, refs);

            boom.commits().commit("main", List.of(
                    FileChange.put("a.txt", bytes("a\n")),
                    FileChange.put("b.txt", bytes("b\n"))), signature(), "Initial commit");
            boom.branches().createBranchFrom("feature", "main");
            boom.commits().commit("main",
                    List.of(FileChange.put("a.txt", bytes("main\n"))), signature(), "Main edit");
            boom.commits().commit("feature",
                    List.of(FileChange.put("b.txt", bytes("feature\n"))), signature(), "Feature edit");

            ObjectId beforeMerge = boom.branches().getBranch("main").orElseThrow();

            // The merged tree persists; the merge commit does not.
            store.failOnWritingType(ObjectType.COMMIT);

            assertThatThrownBy(() -> boom.merges().merge("main", "feature", signature(), "Doomed merge"))
                    .isInstanceOf(ObjectStoreException.class);

            // The branch is updated only after the commit is durable, so it
            // still points exactly where it did.
            assertThat(boom.branches().getBranch("main")).contains(beforeMerge);
            assertThat(new String(boom.reader().readFile("main", "b.txt").orElseThrow(),
                    StandardCharsets.UTF_8)).isEqualTo("b\n");
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        void refusesAnUnknownBranch() {
            commit("main", "Initial commit", FileChange.put("a.txt", bytes("a\n")));

            assertThatThrownBy(() -> merge("main", "ghost"))
                    .isInstanceOf(RefException.class).hasMessageContaining("does not exist");
            assertThatThrownBy(() -> merge("ghost", "main"))
                    .isInstanceOf(RefException.class);
        }

        @Test
        void refusesAMissingBranchName() {
            commit("main", "Initial commit", FileChange.put("a.txt", bytes("a\n")));

            assertThatThrownBy(() -> merge("main", "")).isInstanceOf(RefException.class);
            assertThatThrownBy(() -> merge(null, "main")).isInstanceOf(RefException.class);
        }
    }
}
