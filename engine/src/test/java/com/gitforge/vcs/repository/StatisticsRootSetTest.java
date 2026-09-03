package com.gitforge.vcs.repository;

import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.ObjectType;
import com.gitforge.vcs.object.Signature;
import com.gitforge.vcs.object.Tag;
import com.gitforge.vcs.ref.Head;
import com.gitforge.vcs.ref.ReferenceRoots;
import com.gitforge.vcs.ref.TagService;
import com.gitforge.vcs.worktree.WorkTreeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The root set statistics count from, and why it must be the collector's.
 *
 * <p>For two versions these disagreed. Collection read branches, HEAD,
 * remote-tracking refs and tags; statistics read branches alone. A commit
 * reachable only through a tag was therefore protected from deletion and absent
 * from every figure — the repository keeping work that its own statistics said
 * did not exist.
 *
 * <p>Each test below removes one root's contribution and asserts the commit is
 * still counted, so the suite fails if any single root is dropped from
 * {@link ReferenceRoots}. That is the falsification: a test that only ever passes
 * proves nothing about which line of code is doing the work.
 */
class StatisticsRootSetTest {

    @TempDir
    Path storageRoot;

    private VcsRepository repository;
    private int sequence;

    private static final Signature TAGGER = new Signature(
            "Ada Lovelace", "ada@example.test", Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        repository = new VcsRepositoryFactory(storageRoot).initialise(RepositoryId.of("demo"), "main");
    }

    private ObjectId commit(String branch, String message, String content) {
        return repository.commits().commit(
                branch,
                List.of(FileChange.put("f" + sequence + ".txt", content.getBytes(StandardCharsets.UTF_8))),
                Signature.of("Ada", "ada@example.test", Instant.ofEpochSecond(1_700_000_000L + sequence)),
                message + " " + sequence++);
    }

    /** History on a side branch, then the branch removed so only the new root speaks for it. */
    private ObjectId strandedHistory() {
        ObjectId tip = commit("side", "Side work", "side\n");
        return tip;
    }

    private void dropSideBranch() {
        repository.branches().deleteBranch("side");
    }

    private int countedCommits() {
        return repository.statistics().compute().commits();
    }

    private boolean counts(ObjectId commit) {
        return repository.statistics().reachableCommits().contains(commit);
    }

    @Nested
    @DisplayName("each root contributes")
    class EachRoot {

        @Test
        void branchReachableHistoryIsCounted() {
            ObjectId tip = commit("main", "On main", "a\n");

            assertThat(counts(tip)).isTrue();
            assertThat(countedCommits()).isEqualTo(1);
        }

        @Test
        void headOnlyHistoryIsCounted() {
            ObjectId stranded = strandedHistory();
            ObjectId onMain = commit("main", "On main", "m\n");

            // Detach HEAD onto the stranded commit, then remove the branch, so HEAD
            // is the only thing naming it.
            repository.refs().setHead(Head.detachedAt(stranded));
            dropSideBranch();

            assertThat(repository.branches().listBranches()).containsExactly("main");
            assertThat(counts(stranded)).isTrue();
            assertThat(counts(onMain)).isTrue();
        }

        @Test
        void remoteTrackingOnlyHistoryIsCounted() {
            ObjectId fetched = strandedHistory();
            dropSideBranch();

            repository.refs().setRemoteRef("origin", "main", fetched);

            assertThat(repository.branches().listBranches()).doesNotContain("side");
            assertThat(counts(fetched)).isTrue();
        }

        @Test
        void tagOnlyHistoryIsCounted() {
            ObjectId released = strandedHistory();
            dropSideBranch();

            repository.tags().createLightweight("v1.0.0", released);

            // The case that was broken: no branch reaches it, a tag does.
            assertThat(counts(released)).isTrue();
        }

        @Test
        void annotatedTagOnlyHistoryIsCountedThroughTheTagObject() {
            ObjectId released = strandedHistory();
            dropSideBranch();

            Tag tag = repository.tags().createAnnotated("v1.0.0", released, TAGGER, "Release\n");

            // The ref names the tag object, not the commit, so this only passes if
            // the root is peeled.
            assertThat(repository.refs().getTag("v1.0.0")).contains(tag.id());
            assertThat(tag.targetType()).isEqualTo(ObjectType.COMMIT);
            assertThat(counts(released)).isTrue();

            // And the tag object itself is not mistaken for a commit.
            assertThat(repository.statistics().reachableCommits()).doesNotContain(tag.id());
        }

        @Test
        void aTagChainIsPeeledAllTheWayToTheCommit() {
            ObjectId released = strandedHistory();
            dropSideBranch();

            Tag inner = repository.tags().createAnnotated("inner", released, TAGGER, "Inner\n");
            repository.tags().createAnnotated("outer", inner.id(), TAGGER, "Outer\n");
            repository.tags().deleteTag("inner");

            assertThat(repository.refs().listTags()).containsExactly("outer");
            assertThat(counts(released)).isTrue();
        }
    }

    @Nested
    @DisplayName("what must not be counted")
    class NotCounted {

        @Test
        void unreachableHistoryIsExcluded() {
            ObjectId onMain = commit("main", "On main", "m\n");
            ObjectId stranded = strandedHistory();
            dropSideBranch();

            // Nothing names it now — no branch, no HEAD, no tag, no tracking ref.
            assertThat(counts(stranded)).isFalse();
            assertThat(counts(onMain)).isTrue();
            assertThat(countedCommits()).isEqualTo(1);
        }

        @Test
        void aTagObjectIsNotItselfCountedAsACommit() {
            ObjectId tip = commit("main", "On main", "m\n");
            Tag tag = repository.tags().createAnnotated("v1", tip, TAGGER, "Release\n");

            assertThat(countedCommits()).isEqualTo(1);
            assertThat(repository.statistics().reachableCommits()).doesNotContain(tag.id());
        }

        @Test
        void aWorkTreeRootNamesATreeAndSoContributesNoCommit() {
            ObjectId tip = commit("main", "On main", "m\n");
            ObjectId tree = repository.objects().readCommit(tip).tree();

            WorkTreeState workTree = new WorkTreeState(storageRoot.resolve("worktree-state"));
            workTree.record(tree);

            // The tree is a root — collection must keep it — but it is not a commit,
            // and counting must not invent one from it.
            assertThat(ReferenceRoots.of(repository.refs(), workTree)).contains(tree);

            RepositoryStatistics withWorkTree = new RepositoryStatistics(
                    repository.objects(),
                    repository.branches(),
                    repository.refs(),
                    workTree,
                    new com.gitforge.vcs.graph.CommitGraph(repository.objects()));

            assertThat(withWorkTree.reachableCommits()).containsExactly(tip);
        }
    }

    @Nested
    @DisplayName("deduplication")
    class Deduplication {

        @Test
        void aCommitNamedByEveryKindOfRootIsCountedOnce() {
            ObjectId tip = commit("main", "On main", "m\n");

            repository.branches().createBranch("also", tip);
            repository.tags().createLightweight("v1", tip);
            repository.tags().createAnnotated("v2", tip, TAGGER, "Release\n");
            repository.refs().setRemoteRef("origin", "main", tip);
            repository.refs().setHead(Head.detachedAt(tip));

            assertThat(countedCommits()).isEqualTo(1);
            assertThat(repository.statistics().reachableCommits()).containsExactly(tip);
        }

        @Test
        void theRootSetKeepsDuplicatesSoACountIsNotOrderDependent() {
            ObjectId tip = commit("main", "On main", "m\n");
            repository.tags().createLightweight("v1", tip);
            repository.refs().setHead(Head.onBranch("main"));

            // main, HEAD and v1 all name the same commit; the root list reports
            // three, matching what collection reports.
            assertThat(ReferenceRoots.of(repository.refs(), null)).hasSize(3);
        }
    }

    @Nested
    @DisplayName("statistics and collection agree")
    class AgreementWithCollection {

        @Test
        void everyCountedCommitSurvivesCollection() {
            ObjectId onMain = commit("main", "On main", "m\n");
            ObjectId tagged = strandedHistory();
            dropSideBranch();
            repository.tags().createLightweight("v1.0.0", tagged);

            var counted = repository.statistics().reachableCommits();
            assertThat(counted).contains(onMain, tagged);

            repository.gc().collect();

            // The statistic and the sweep now answer the same question the same way.
            for (ObjectId id : counted) {
                assertThat(repository.objects().contains(id))
                        .as("counted commit %s survived collection", id)
                        .isTrue();
            }
        }

        @Test
        void anUncountedCommitIsExactlyWhatCollectionRemoves() {
            commit("main", "On main", "m\n");
            ObjectId stranded = strandedHistory();
            dropSideBranch();

            assertThat(counts(stranded)).isFalse();

            var report = repository.gc().collect();

            assertThat(report.collected()).contains(stranded);
            assertThat(repository.objects().contains(stranded)).isFalse();
        }
    }
}
