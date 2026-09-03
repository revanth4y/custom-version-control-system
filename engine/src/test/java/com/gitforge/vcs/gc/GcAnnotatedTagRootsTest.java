package com.gitforge.vcs.gc;

import com.gitforge.vcs.RepositoryFixture;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.ObjectType;
import com.gitforge.vcs.object.Signature;
import com.gitforge.vcs.object.Tag;
import com.gitforge.vcs.ref.Head;
import com.gitforge.vcs.ref.RefStore;
import com.gitforge.vcs.ref.TagService;
import com.gitforge.vcs.repository.RepositoryLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;

import static com.gitforge.vcs.RepositoryFixture.files;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Collection against annotated tags, and against chains of them.
 *
 * <p>An annotated tag introduces something no other root does: the ref names a tag
 * object rather than the history itself, so protecting the root is not enough — the
 * closure has to follow the tag on to its target, and on again if that target is
 * another tag. Two things can therefore go wrong independently, and each has a test
 * that fails when the corresponding line is removed:
 *
 * <ul>
 *   <li>the root missing, which loses the tag object itself;
 *   <li>the traversal missing, which keeps the tag object and destroys the very
 *       history it names — the more insidious of the two, because the tag survives
 *       and points at nothing.
 * </ul>
 */
class GcAnnotatedTagRootsTest {

    @TempDir
    Path tempDir;

    private RepositoryFixture repository;
    private RefStore refs;
    private TagService tags;
    private ObjectId mainTip;

    private static final Signature TAGGER = new Signature(
            "Ada Lovelace",
            "ada@example.test",
            Instant.ofEpochSecond(1_700_000_000L),
            ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        repository = new RepositoryFixture(tempDir.resolve("repo"), tempDir.resolve("work"));
        refs = repository.refStore();
        tags = new TagService(refs, repository.objectStore(), new RepositoryLock());

        mainTip = repository.commit("Ongoing work", null, files("README.md", "# Demo\n"));
        repository.branches().createBranch("main", mainTip);
        refs.setHead(Head.onBranch("main"));
    }

    private GarbageCollector collector() {
        return new GarbageCollector(
                repository.objectStore(), refs, repository.workTreeState(), new RepositoryLock());
    }

    private boolean stored(ObjectId id) {
        return repository.objectStore().contains(id);
    }

    /** History no branch reaches — the shape a release tag is created to preserve. */
    private ObjectId releasedHistory() {
        return repository.commit("Version 1.0", null, files("VERSION", "1.0\n"));
    }

    // ---------------------------------------------------------------- B

    @Test
    @DisplayName("an annotated tag's object and its target both survive collection")
    void anAnnotatedTagProtectsItselfAndWhatItNames() {
        ObjectId released = releasedHistory();
        ObjectId releasedTree = repository.objectStore().readCommit(released).tree();

        Tag tag = tags.createAnnotated("v1.0.0", released, TAGGER, "Release 1.0\n");

        // The ref names the tag object, and only the tag object names the commit.
        assertThat(refs.getTag("v1.0.0")).contains(tag.id());
        assertThat(refs.getTag("v1.0.0").orElseThrow()).isNotEqualTo(released);

        GcReport report = collector().collect();

        assertThat(report.collected()).isEmpty();
        assertThat(report.unreachable()).isEmpty();

        assertThat(stored(tag.id())).isTrue();
        assertThat(stored(released)).isTrue();
        assertThat(stored(releasedTree)).isTrue();

        // Still readable, and still saying the same thing.
        Tag readBack = tags.annotationOf("v1.0.0").orElseThrow();
        assertThat(readBack.target()).isEqualTo(released);
        assertThat(readBack.message()).isEqualTo("Release 1.0\n");
        assertThat(readBack.targetType()).isEqualTo(ObjectType.COMMIT);
    }

    @Test
    @DisplayName("once the annotated tag is gone, its object and target become collectible")
    void deletingTheAnnotatedTagReleasesBoth() {
        ObjectId released = releasedHistory();
        Tag tag = tags.createAnnotated("v1.0.0", released, TAGGER, "Release 1.0\n");

        assertThat(collector().collect().collected()).isEmpty();

        assertThat(tags.deleteTag("v1.0.0")).isTrue();

        // Deleting the ref reclaims nothing by itself.
        assertThat(stored(tag.id())).isTrue();
        assertThat(stored(released)).isTrue();

        GcReport report = collector().collect();

        assertThat(report.collected()).contains(tag.id(), released);
        assertThat(stored(tag.id())).isFalse();
        assertThat(stored(released)).isFalse();

        // The branch history is untouched throughout.
        assertThat(stored(mainTip)).isTrue();
    }

    @Test
    @DisplayName("the whole history beneath an annotated tag survives, not just the tagged commit")
    void theAncestryBeneathAnAnnotatedTagSurvives() {
        ObjectId first = repository.commit("First", null, files("a.txt", "one\n"));
        ObjectId second = repository.commit("Second", first, files("a.txt", "two\n"));
        ObjectId firstTree = repository.objectStore().readCommit(first).tree();

        tags.createAnnotated("v1.0.0", second, TAGGER, "Release\n");

        collector().collect();

        assertThat(stored(first)).isTrue();
        assertThat(stored(second)).isTrue();
        assertThat(stored(firstTree)).isTrue();
    }

    // ---------------------------------------------------------------- C

    @Test
    @DisplayName("a tag-to-tag chain survives collection end to end")
    void aChainOfTagsProtectsEveryLinkAndTheTarget() {
        ObjectId released = releasedHistory();

        // A -> B -> commit, where only A has a ref.
        Tag inner = tags.createAnnotated("v1.0.0", released, TAGGER, "Inner\n");
        Tag outer = tags.createAnnotated("latest", inner.id(), TAGGER, "Outer\n");

        assertThat(outer.targetType()).isEqualTo(ObjectType.TAG);
        assertThat(outer.pointsAtATag()).isTrue();

        // Remove the ref that names the inner tag directly, so the only thing
        // speaking for it is the outer tag's target field.
        assertThat(tags.deleteTag("v1.0.0")).isTrue();
        assertThat(refs.listTags()).containsExactly("latest");

        GcReport report = collector().collect();

        assertThat(report.collected()).isEmpty();
        assertThat(stored(outer.id())).isTrue();
        assertThat(stored(inner.id())).isTrue();
        assertThat(stored(released)).isTrue();
    }

    @Test
    @DisplayName("a three-deep tag chain is followed all the way down")
    void aThreeDeepChainIsFollowedToTheCommit() {
        ObjectId released = releasedHistory();

        Tag one = tags.createAnnotated("a", released, TAGGER, "One\n");
        Tag two = tags.createAnnotated("b", one.id(), TAGGER, "Two\n");
        Tag three = tags.createAnnotated("c", two.id(), TAGGER, "Three\n");

        tags.deleteTag("a");
        tags.deleteTag("b");
        assertThat(refs.listTags()).containsExactly("c");

        GcReport report = collector().collect();

        assertThat(report.collected()).isEmpty();
        assertThat(stored(three.id())).isTrue();
        assertThat(stored(two.id())).isTrue();
        assertThat(stored(one.id())).isTrue();
        assertThat(stored(released)).isTrue();
    }

    @Test
    @DisplayName("dropping the chain's only ref makes every link collectible")
    void deletingTheChainRootReleasesTheWholeChain() {
        ObjectId released = releasedHistory();
        Tag inner = tags.createAnnotated("v1.0.0", released, TAGGER, "Inner\n");
        Tag outer = tags.createAnnotated("latest", inner.id(), TAGGER, "Outer\n");
        tags.deleteTag("v1.0.0");

        assertThat(collector().collect().collected()).isEmpty();

        assertThat(tags.deleteTag("latest")).isTrue();

        GcReport report = collector().collect();

        assertThat(report.collected()).contains(outer.id(), inner.id(), released);
        assertThat(stored(outer.id())).isFalse();
        assertThat(stored(inner.id())).isFalse();
        assertThat(stored(released)).isFalse();
        assertThat(stored(mainTip)).isTrue();
    }

    @Test
    @DisplayName("an annotated tag and a lightweight tag protect the same commit independently")
    void bothKindsOfTagCountAsRoots() {
        ObjectId released = releasedHistory();

        Tag annotated = tags.createAnnotated("v1.0.0", released, TAGGER, "Release\n");
        tags.createLightweight("stable", released);

        // Removing the annotated one leaves the lightweight one speaking for the
        // commit, but the tag object itself is now unreferenced.
        tags.deleteTag("v1.0.0");

        GcReport report = collector().collect();

        assertThat(report.collected()).containsExactly(annotated.id());
        assertThat(stored(released)).isTrue();
        assertThat(refs.getTag("stable")).contains(released);
    }
}
