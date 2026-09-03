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
 * Collection against fetched objects.
 *
 * <p>A fetch writes objects that no local branch reaches — that is what makes it
 * a fetch rather than a merge. If a remote-tracking ref is not a root, then the
 * very next sweep deletes everything the fetch brought in, and the tracking ref
 * is left pointing at a commit whose objects are gone.
 *
 * <p>These two tests are the pair that pins it: the first proves a fetched object
 * survives, and the second proves the protection is the tracking ref itself
 * rather than something incidental, by removing it and watching the same object
 * become collectible.
 */
class GcRemoteRootsTest {

    @TempDir
    Path tempDir;

    private RepositoryFixture repository;
    private RefStore refs;
    private ObjectId localTip;

    @BeforeEach
    void setUp() {
        repository = new RepositoryFixture(tempDir.resolve("repo"), tempDir.resolve("work"));
        refs = repository.refStore();

        localTip = repository.commit("Local work", null, files("README.md", "# Demo\n"));
        repository.branches().createBranch("main", localTip);
        refs.setHead(Head.onBranch("main"));
    }

    private GarbageCollector collector() {
        return new GarbageCollector(
                repository.objectStore(), refs, repository.workTreeState(), new RepositoryLock());
    }

    private Set<ObjectId> stored() {
        return Set.copyOf(repository.objectStore().listIds());
    }

    /**
     * History that exists only because it was fetched: written to the store, and
     * reachable from nothing local.
     */
    private ObjectId fetchedHistory() {
        return repository.commit("Only on the remote", null, files("remote.txt", "theirs\n"));
    }

    @Test
    @DisplayName("an object reachable only through a remote-tracking ref survives collection")
    void aFetchedObjectSurvivesCollection() {
        ObjectId fetched = fetchedHistory();
        ObjectId fetchedTree = repository.objectStore().readCommit(fetched).tree();

        // Nothing local reaches it - this is exactly what a sweep would otherwise
        // read as garbage.
        assertThat(refs.listBranches()).containsExactly("main");
        assertThat(repository.branches().getBranch("main")).contains(localTip);

        refs.setRemoteRef("origin", "main", fetched);

        GcReport report = collector().collect();

        assertThat(report.collected()).isEmpty();
        assertThat(report.unreachable()).isEmpty();
        assertThat(repository.objectStore().contains(fetched)).isTrue();
        assertThat(repository.objectStore().contains(fetchedTree)).isTrue();
        assertThat(repository.objectStore().readCommit(fetched).message())
                .isEqualTo("Only on the remote\n");

        // The tracking ref is counted as a root in its own right: main, HEAD, and
        // origin/main.
        assertThat(report.roots()).isEqualTo(3);
    }

    @Test
    @DisplayName("once the remote-tracking ref is gone, the same object becomes collectible")
    void droppingTheTrackingRefMakesTheObjectCollectible() {
        ObjectId fetched = fetchedHistory();
        refs.setRemoteRef("origin", "main", fetched);

        Set<ObjectId> withTracking = stored();
        assertThat(collector().collect().collected()).isEmpty();

        // Drop the only thing that spoke for it.
        assertThat(refs.deleteRemoteRefs("origin")).isEqualTo(1);
        assertThat(refs.listRemoteRefs()).isEmpty();

        // Dropping the ref does not itself reclaim anything, exactly as branch
        // deletion does not.
        assertThat(stored()).isEqualTo(withTracking);
        assertThat(repository.objectStore().contains(fetched)).isTrue();

        GcReport report = collector().collect();

        assertThat(report.collected()).contains(fetched);
        assertThat(repository.objectStore().contains(fetched)).isFalse();

        // And the local history is untouched throughout.
        assertThat(repository.objectStore().contains(localTip)).isTrue();
        assertThat(repository.branches().getBranch("main")).contains(localTip);
    }

    @Test
    @DisplayName("a tracking ref protects the whole history beneath it, not just its tip")
    void theHistoryBeneathAFetchedTipSurvivesToo() {
        ObjectId parent = repository.commit("Their first", null, files("remote.txt", "one\n"));
        ObjectId tip = repository.commit("Their second", parent, files("remote.txt", "two\n"));
        refs.setRemoteRef("origin", "main", tip);

        collector().collect();

        // The parent is reachable only as a parent of a fetched tip.
        assertThat(repository.objectStore().contains(parent)).isTrue();
        assertThat(repository.objectStore().contains(tip)).isTrue();
    }

    @Test
    @DisplayName("one remote's refs do not protect another's objects")
    void droppingOneRemoteLeavesAnothersObjectsProtected() {
        ObjectId theirs = repository.commit("Theirs", null, files("a.txt", "a\n"));
        ObjectId backups = repository.commit("Backup's", null, files("b.txt", "b\n"));
        refs.setRemoteRef("origin", "main", theirs);
        refs.setRemoteRef("backup", "main", backups);

        refs.deleteRemoteRefs("origin");
        GcReport report = collector().collect();

        assertThat(report.collected()).contains(theirs);
        assertThat(report.collected()).doesNotContain(backups);
        assertThat(repository.objectStore().contains(backups)).isTrue();
    }
}
