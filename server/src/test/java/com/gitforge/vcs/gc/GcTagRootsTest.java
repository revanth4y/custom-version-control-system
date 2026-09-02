package com.gitforge.vcs.gc;

import com.gitforge.vcs.RepositoryFixture;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.ref.Head;
import com.gitforge.vcs.ref.RefStore;
import com.gitforge.vcs.repository.RepositoryLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static com.gitforge.vcs.RepositoryFixture.files;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Collection against tagged history.
 *
 * <p>A tag exists so that a point in history stays reachable after the branch
 * that produced it has moved on or been deleted. If {@code refs/tags} is not a
 * root, the next sweep deletes exactly the history the tag was created to keep,
 * and leaves the tag pointing at a commit whose objects are gone — the same
 * failure remote-tracking refs were protected from, arriving through a different
 * door.
 *
 * <p>These tests are written as a pair wherever it is possible: one proves the
 * object survives, the next proves the protection is the tag itself rather than
 * something incidental, by removing the tag and watching the same object become
 * collectible. A test that only ever passes proves nothing about which line of
 * code is doing the work.
 *
 * <p>Annotated tags and tag-to-tag chains are covered in {@code
 * GcAnnotatedTagRootsTest}, which needs the tag object type to exist.
 */
class GcTagRootsTest {

    @TempDir
    Path tempDir;

    private RepositoryFixture repository;
    private RefStore refs;
    private ObjectId mainTip;

    @BeforeEach
    void setUp() {
        repository = new RepositoryFixture(tempDir.resolve("repo"), tempDir.resolve("work"));
        refs = repository.refStore();

        mainTip = repository.commit("Ongoing work", null, files("README.md", "# Demo\n"));
        repository.branches().createBranch("main", mainTip);
        refs.setHead(Head.onBranch("main"));
    }

    private GarbageCollector collector() {
        return new GarbageCollector(
                repository.objectStore(), refs, repository.workTreeState(), new RepositoryLock());
    }

    private Set<ObjectId> stored() {
        return Set.copyOf(repository.objectStore().listIds());
    }

    /** History no branch reaches — the shape a tag is created to preserve. */
    private ObjectId releasedHistory() {
        return repository.commit("Version 1.0", null, files("VERSION", "1.0\n"));
    }

    @Test
    @DisplayName("an object reachable only through a tag survives collection")
    void aTaggedObjectSurvivesCollection() {
        ObjectId released = releasedHistory();
        ObjectId releasedTree = repository.objectStore().readCommit(released).tree();

        // Nothing local reaches it: this is precisely what a sweep would
        // otherwise read as garbage.
        assertThat(refs.listBranches()).containsExactly("main");
        assertThat(repository.branches().getBranch("main")).contains(mainTip);

        refs.createTag("v1.0.0", released);

        GcReport report = collector().collect();

        assertThat(report.collected()).isEmpty();
        assertThat(report.unreachable()).isEmpty();
        assertThat(repository.objectStore().contains(released)).isTrue();
        assertThat(repository.objectStore().contains(releasedTree)).isTrue();
        assertThat(repository.objectStore().readCommit(released).message())
                .isEqualTo("Version 1.0\n");

        // The tag counts as a root in its own right: main, HEAD, and v1.0.0.
        assertThat(report.roots()).isEqualTo(3);
    }

    @Test
    @DisplayName("once the tag is gone, the same object becomes collectible")
    void deletingTheTagMakesTheObjectCollectible() {
        ObjectId released = releasedHistory();
        refs.createTag("v1.0.0", released);

        Set<ObjectId> withTag = stored();
        assertThat(collector().collect().collected()).isEmpty();

        // Drop the only thing that spoke for it.
        assertThat(refs.deleteTag("v1.0.0")).isTrue();
        assertThat(refs.listTags()).isEmpty();

        // Deleting the tag does not itself reclaim anything, exactly as branch
        // deletion does not.
        assertThat(stored()).isEqualTo(withTag);
        assertThat(repository.objectStore().contains(released)).isTrue();

        GcReport report = collector().collect();

        assertThat(report.collected()).contains(released);
        assertThat(repository.objectStore().contains(released)).isFalse();

        // And the branch history is untouched throughout.
        assertThat(repository.objectStore().contains(mainTip)).isTrue();
        assertThat(repository.branches().getBranch("main")).contains(mainTip);
    }

    @Test
    @DisplayName("a tag protects the whole history beneath it, not just its tip")
    void theHistoryBeneathATaggedTipSurvivesToo() {
        ObjectId first = repository.commit("First", null, files("a.txt", "one\n"));
        ObjectId second = repository.commit("Second", first, files("a.txt", "two\n"));
        ObjectId firstTree = repository.objectStore().readCommit(first).tree();
        refs.createTag("v1.0.0", second);

        collector().collect();

        // The parent is reachable only as a parent of a tagged tip.
        assertThat(repository.objectStore().contains(first)).isTrue();
        assertThat(repository.objectStore().contains(second)).isTrue();
        assertThat(repository.objectStore().contains(firstTree)).isTrue();
    }

    @Test
    @DisplayName("a tag keeps history alive after the branch that produced it is deleted")
    void aTagOutlivesTheBranchItWasCutFrom() {
        ObjectId released = repository.commit("Version 1.0", null, files("VERSION", "1.0\n"));
        repository.branches().createBranch("release", released);
        refs.createTag("v1.0.0", released);

        repository.branches().deleteBranch("release");

        GcReport report = collector().collect();

        // This is the whole purpose of a tag, stated as a test.
        assertThat(report.collected()).isEmpty();
        assertThat(repository.objectStore().contains(released)).isTrue();
        assertThat(refs.getTag("v1.0.0")).contains(released);
    }

    @Test
    @DisplayName("one tag's deletion does not expose another tag's objects")
    void deletingOneTagLeavesAnothersObjectsProtected() {
        ObjectId one = repository.commit("One", null, files("a.txt", "a\n"));
        ObjectId two = repository.commit("Two", null, files("b.txt", "b\n"));
        refs.createTag("v1", one);
        refs.createTag("v2", two);

        assertThat(refs.deleteTag("v1")).isTrue();

        GcReport report = collector().collect();

        assertThat(report.collected()).contains(one);
        assertThat(repository.objectStore().contains(two)).isTrue();
        assertThat(refs.getTag("v2")).contains(two);
    }

    @Test
    @DisplayName("tags are counted as roots alongside branches, HEAD and tracking refs")
    void everyKindOfReferenceContributesARoot() {
        ObjectId tagged = repository.commit("Tagged", null, files("t.txt", "t\n"));
        ObjectId fetched = repository.commit("Fetched", null, files("f.txt", "f\n"));
        refs.createTag("v1", tagged);
        refs.setRemoteRef("origin", "main", fetched);

        GcReport report = collector().collect();

        // main, HEAD, origin/main, v1.
        assertThat(report.roots()).isEqualTo(4);
        assertThat(report.collected()).isEmpty();
    }
}
