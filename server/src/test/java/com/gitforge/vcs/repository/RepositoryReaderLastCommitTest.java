package com.gitforge.vcs.repository;

import com.gitforge.vcs.CountingObjectStore;
import com.gitforge.vcs.graph.CommitGraph;
import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which commit last touched each path in a listing.
 *
 * <p>The interesting cases are not the happy one. A directory has to be
 * credited with changes to its contents, a merge has to be attributed the way
 * the rest of the reader attributes it, and a path whose last change falls
 * outside the search window has to come back unknown rather than wrong.
 */
class RepositoryReaderLastCommitTest {

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

    private ObjectId commit(String message, FileChange... changes) {
        return commit("main", message, changes);
    }

    private Map<String, Commit> lastCommits(List<String> paths) {
        return repository.reader().lastCommits("main", paths, 200);
    }

    /** The commit subject, without the trailing newline the engine stores. */
    private static String subjectOf(Map<String, Commit> resolved, String path) {
        return resolved.get(path).message().strip();
    }

    @Test
    void creditsEachPathToTheCommitThatLastTouchedIt() {
        commit("Initial commit",
                FileChange.put("README.md", bytes("one\n")),
                FileChange.put("pom.xml", bytes("pom\n")));
        ObjectId edited = commit("Edit the readme", FileChange.put("README.md", bytes("two\n")));

        Map<String, Commit> result = lastCommits(List.of("README.md", "pom.xml"));

        assertThat(result.get("README.md").id()).isEqualTo(edited);
        assertThat(subjectOf(result, "README.md")).isEqualTo("Edit the readme");
        // Untouched since the first commit, so that is still its answer.
        assertThat(subjectOf(result, "pom.xml")).isEqualTo("Initial commit");
    }

    /**
     * A directory is almost never changed in its own right; it is changed when
     * something inside it is. Without this the column would read "Initial
     * commit" against every folder forever.
     */
    @Test
    void aDirectoryIsTouchedByChangesBeneathIt() {
        commit("Initial commit",
                FileChange.put("src/App.java", bytes("app\n")),
                FileChange.put("docs/guide.md", bytes("guide\n")));
        ObjectId deep = commit("Add a utility", FileChange.put("src/deep/Util.java", bytes("util\n")));

        Map<String, Commit> result = lastCommits(List.of("src", "docs"));

        assertThat(result.get("src").id()).isEqualTo(deep);
        assertThat(subjectOf(result, "docs")).isEqualTo("Initial commit");
    }

    /** A prefix that is not a path segment must not count as a match. */
    @Test
    void aSimilarlyNamedSiblingIsNotMistakenForAChild() {
        commit("Initial commit", FileChange.put("src/App.java", bytes("app\n")));
        ObjectId sibling = commit("Add a lookalike", FileChange.put("src-generated/Thing.java", bytes("x\n")));

        Map<String, Commit> result = lastCommits(List.of("src", "src-generated"));

        assertThat(subjectOf(result, "src")).isEqualTo("Initial commit");
        assertThat(result.get("src-generated").id()).isEqualTo(sibling);
    }

    @Test
    void deletionCountsAsTouchingThePath() {
        commit("Initial commit",
                FileChange.put("keep.txt", bytes("keep\n")),
                FileChange.put("doomed.txt", bytes("bye\n")));
        ObjectId removed = commit("Remove the file", FileChange.delete("doomed.txt"));

        // The path is gone from the tree, but a listing of an older revision can
        // still ask about it, and the deletion is the honest answer.
        assertThat(lastCommits(List.of("doomed.txt")).get("doomed.txt").id()).isEqualTo(removed);
    }

    /**
     * A merge is credited with what it brought in relative to its first parent,
     * matching {@code changesIn} and therefore the rest of the reader.
     */
    @Test
    void aMergeIsAttributedTheChangesItBroughtIn() {
        ObjectId base = commit("Initial commit", FileChange.put("README.md", bytes("base\n")));

        repository.branches().createBranch("topic", base);
        commit("topic", "Work on the topic", FileChange.put("feature.txt", bytes("feature\n")));

        commit("Carry on with main", FileChange.put("README.md", bytes("main\n")));

        repository.merges().merge("main", "topic", signature(), "Merge topic");

        Map<String, Commit> result = lastCommits(List.of("feature.txt", "README.md"));

        assertThat(result.get("feature.txt").isMerge()).isTrue();
        assertThat(subjectOf(result, "feature.txt")).isEqualTo("Merge topic");
        // The merge did not change the readme relative to its first parent.
        assertThat(subjectOf(result, "README.md")).isEqualTo("Carry on with main");
    }

    /**
     * The whole point of the bound. A path whose last change is older than the
     * window comes back absent, and the caller renders that as unknown rather
     * than attributing it to whatever commit happened to be in range.
     */
    @Test
    void aPathUnchangedWithinTheWindowIsUnresolved() {
        commit("Initial commit",
                FileChange.put("ancient.txt", bytes("old\n")),
                FileChange.put("busy.txt", bytes("0\n")));

        for (int i = 1; i <= 5; i++) {
            commit("Edit " + i, FileChange.put("busy.txt", bytes(i + "\n")));
        }

        // Only the three most recent commits are examined.
        Map<String, Commit> result = repository.reader()
                .lastCommits("main", List.of("ancient.txt", "busy.txt"), 3);

        assertThat(result).containsKey("busy.txt");
        assertThat(result).doesNotContainKey("ancient.txt");
    }

    /**
     * Once every path is accounted for the walk stops, which is what keeps a
     * listing cheap on a long history.
     *
     * <p>That is a claim about work <em>not</em> done, so a correct answer
     * proves nothing on its own. Counting object reads makes it measurable: a
     * query resolved by the newest commit must read far less than one that has
     * to look all the way back.
     */
    @Test
    void theWalkStopsOnceEveryPathIsResolved() {
        commit("Initial commit",
                FileChange.put("a.txt", bytes("a\n")),
                FileChange.put("ancient.txt", bytes("old\n")));
        for (int i = 1; i <= 50; i++) {
            commit("Edit " + i, FileChange.put("a.txt", bytes(i + "\n")));
        }

        CountingObjectStore counting = new CountingObjectStore(repository.objects());
        RepositoryReader reader = new RepositoryReader(
                counting, repository.branches(), new CommitGraph(counting));

        counting.resetCounts();
        Map<String, Commit> resolvedImmediately = reader.lastCommits("main", List.of("a.txt"), 200);
        int readsWhenResolvedEarly = counting.readCount();

        counting.resetCounts();
        // ancient.txt was last touched by the very first commit, so this one
        // cannot stop early and has to walk the whole window.
        Map<String, Commit> resolvedLate = reader.lastCommits("main", List.of("ancient.txt"), 200);
        int readsWhenWalkingFully = counting.readCount();

        assertThat(subjectOf(resolvedImmediately, "a.txt")).isEqualTo("Edit 50");
        assertThat(subjectOf(resolvedLate, "ancient.txt")).isEqualTo("Initial commit");
        // Not a dramatic ratio, and it should not be: ordering the window newest
        // first means the commits themselves are read up front either way. What
        // early termination saves is the tree diffing, which is the expensive
        // half - so the saving is real but bounded by that fixed cost.
        assertThat(readsWhenResolvedEarly)
                .as("stopping at the first hit must cost meaningfully less than a full walk")
                .isLessThan(readsWhenWalkingFully / 2);
    }

    @Test
    void anEmptyRepositoryResolvesNothing() {
        assertThat(repository.reader().lastCommits("main", List.of("README.md"), 200)).isEmpty();
    }

    @Test
    void noPathsAsksNothingOfTheHistory() {
        commit("Initial commit", FileChange.put("README.md", bytes("one\n")));

        assertThat(repository.reader().lastCommits("main", List.of(), 200)).isEmpty();
        assertThat(repository.reader().lastCommits("main", null, 200)).isEmpty();
    }

    @Test
    void anUnknownRevisionResolvesNothing() {
        commit("Initial commit", FileChange.put("README.md", bytes("one\n")));

        assertThat(repository.reader().lastCommits("no-such-branch", List.of("README.md"), 200)).isEmpty();
    }

    @Test
    void aNonPositiveLimitIsRejected() {
        assertThatThrownBy(() -> repository.reader().lastCommits("main", List.of("README.md"), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }
}
