package com.gitforge.vcs.repository;

import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One repository must never be able to see or disturb another's storage.
 */
class RepositoryIsolationTest {

    @TempDir
    Path storageRoot;

    private VcsRepositoryFactory factory;

    private static final Signature ADA =
            Signature.of("Ada Lovelace", "ada@example.com", Instant.ofEpochSecond(1_700_000_000L));

    @BeforeEach
    void setUp() {
        factory = new VcsRepositoryFactory(storageRoot);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private VcsRepository repository(String id) {
        return factory.initialise(RepositoryId.of(id), "main");
    }

    private ObjectId commit(VcsRepository repository, String path, String content, String message) {
        return repository.commits().commit(
                "main", List.of(FileChange.put(path, bytes(content))), ADA, message);
    }

    @Nested
    @DisplayName("storage layout")
    class Layout {

        @Test
        void eachRepositoryGetsItsOwnDirectory() {
            repository("alpha");
            repository("beta");

            assertThat(storageRoot.resolve("alpha/objects")).isDirectory();
            assertThat(storageRoot.resolve("alpha/refs/heads")).isDirectory();
            assertThat(storageRoot.resolve("alpha/HEAD")).isRegularFile();
            assertThat(storageRoot.resolve("beta/objects")).isDirectory();
        }

        @Test
        void thereIsNoServerSideWorkingTree() throws Exception {
            VcsRepository repository = repository("alpha");
            commit(repository, "README.md", "# Demo\n", "Initial commit");

            try (var entries = Files.list(storageRoot.resolve("alpha"))) {
                assertThat(entries.map(path -> path.getFileName().toString()))
                        .containsExactlyInAnyOrder("objects", "refs", "HEAD");
            }
        }

        @Test
        void aFreshRepositoryHasHeadButNoBranchYet() {
            VcsRepository repository = repository("alpha");

            assertThat(repository.branches().currentBranch()).contains("main");
            assertThat(repository.branches().listBranches()).isEmpty();
            assertThat(repository.branches().headCommit()).isEmpty();
        }

        @Test
        void initialisingAnExistingRepositoryIsRefused() {
            repository("alpha");

            assertThatThrownBy(() -> repository("alpha"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already exists");
        }

        @Test
        void openingAnAbsentRepositoryIsRefused() {
            assertThatThrownBy(() -> factory.open(RepositoryId.of("ghost")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not exist");
        }

        @Test
        void reopeningSeesEverythingPreviouslyWritten() {
            VcsRepository original = repository("alpha");
            ObjectId commitId = commit(original, "README.md", "# Demo\n", "Initial commit");

            VcsRepository reopened = factory.open(RepositoryId.of("alpha"));

            assertThat(reopened.branches().getBranch("main")).contains(commitId);
            assertThat(reopened.reader().readFile("main", "README.md"))
                    .get().isEqualTo(bytes("# Demo\n"));
        }
    }

    @Nested
    @DisplayName("isolation between repositories")
    class Isolation {

        @Test
        void objectStoresAreSeparate() {
            VcsRepository alpha = repository("alpha");
            VcsRepository beta = repository("beta");

            ObjectId inAlpha = commit(alpha, "a.txt", "only in alpha\n", "Alpha commit");

            assertThat(alpha.objects().contains(inAlpha)).isTrue();
            assertThat(beta.objects().contains(inAlpha)).isFalse();
            assertThat(beta.objects().count()).isZero();
        }

        @Test
        void identicalContentSharesAnIdButNotStorage() {
            VcsRepository alpha = repository("alpha");
            VcsRepository beta = repository("beta");

            ObjectId inAlpha = commit(alpha, "same.txt", "identical content\n", "Same message");
            ObjectId inBeta = commit(beta, "same.txt", "identical content\n", "Same message");

            // Content addressing is global, so the ids genuinely match...
            assertThat(inAlpha).isEqualTo(inBeta);
            // ...but each repository holds its own copy, in its own directory.
            assertThat(storageRoot.resolve("alpha/objects")).isDirectory();
            assertThat(storageRoot.resolve("beta/objects")).isDirectory();
            assertThat(alpha.objects().listIds()).isNotEmpty();
            assertThat(beta.objects().listIds()).containsExactlyInAnyOrderElementsOf(alpha.objects().listIds());
        }

        @Test
        void oneRepositoryCannotReadAnothersObjectsEvenKnowingTheId() {
            VcsRepository alpha = repository("alpha");
            VcsRepository beta = repository("beta");

            ObjectId secret = commit(alpha, "secret.txt", "confidential\n", "Alpha only");

            assertThat(beta.objects().read(secret)).isEmpty();
            assertThat(beta.reader().commit(secret)).isEmpty();
        }

        @Test
        void branchesAreSeparate() {
            VcsRepository alpha = repository("alpha");
            VcsRepository beta = repository("beta");
            commit(alpha, "a.txt", "a\n", "Alpha commit");
            commit(beta, "b.txt", "b\n", "Beta commit");

            alpha.branches().createBranchFrom("feature", "main");

            assertThat(alpha.branches().listBranches()).containsExactly("feature", "main");
            assertThat(beta.branches().listBranches()).containsExactly("main");
        }

        @Test
        void headIsSeparate() {
            VcsRepository alpha = repository("alpha");
            VcsRepository beta = repository("beta");
            commit(alpha, "a.txt", "a\n", "Alpha commit");
            alpha.branches().createBranchFrom("feature", "main");

            alpha.refs().setHead(com.gitforge.vcs.ref.Head.onBranch("feature"));

            assertThat(alpha.branches().currentBranch()).contains("feature");
            assertThat(beta.branches().currentBranch()).contains("main");
        }

        @Test
        void sameBranchNameInTwoRepositoriesPointsIndependently() {
            VcsRepository alpha = repository("alpha");
            VcsRepository beta = repository("beta");

            ObjectId alphaTip = commit(alpha, "a.txt", "alpha\n", "Alpha commit");
            ObjectId betaTip = commit(beta, "b.txt", "beta\n", "Beta commit");

            assertThat(alphaTip).isNotEqualTo(betaTip);
            assertThat(alpha.branches().getBranch("main")).contains(alphaTip);
            assertThat(beta.branches().getBranch("main")).contains(betaTip);
        }
    }

    @Nested
    @DisplayName("identifier validation")
    class Identifiers {

        @ParameterizedTest(name = "rejects \"{0}\"")
        @ValueSource(strings = {
                "", "   ",
                "..", ".",
                "../escape", "a/b", "a\\b", "/absolute", "C:/windows",
                "has space", "has:colon", "has*star", "has~tilde",
                "..%2Fescape"
        })
        void rejectsUnsafeIdentifiers(String raw) {
            assertThatThrownBy(() -> RepositoryId.of(raw)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsNullAndOverlongIdentifiers() {
            assertThatThrownBy(() -> RepositoryId.of(null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> RepositoryId.of("a".repeat(65)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at most 64");
        }

        @Test
        void acceptsUuidsAndPlainNames() {
            assertThat(RepositoryId.of("7f3c1b2e-9a4d-4f81-b6c2-0d5e8a1f2b3c").value())
                    .isEqualTo("7f3c1b2e-9a4d-4f81-b6c2-0d5e8a1f2b3c");
            assertThat(RepositoryId.of("my_repo.v2").value()).isEqualTo("my_repo.v2");
        }

        @Test
        void identifiersWithEqualValuesAreEqual() {
            assertThat(RepositoryId.of("alpha")).isEqualTo(RepositoryId.of("alpha"))
                    .hasSameHashCodeAs(RepositoryId.of("alpha"));
        }

        @Test
        void aTraversingIdentifierCannotReachOutsideTheStorageRoot() {
            // Blocked by RepositoryId, and independently by the factory's own
            // containment check.
            assertThatThrownBy(() -> factory.pathFor(RepositoryId.of("..")))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(storageRoot.getParent().resolve("objects")).doesNotExist();
        }

        @Test
        void everyRepositoryPathStaysDirectlyUnderTheStorageRoot() {
            assertThat(factory.pathFor(RepositoryId.of("alpha")).getParent())
                    .isEqualTo(factory.storageRoot());
        }
    }
}
