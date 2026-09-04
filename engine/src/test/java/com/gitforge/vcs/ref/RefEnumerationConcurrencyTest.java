package com.gitforge.vcs.ref;

import com.gitforge.vcs.object.ObjectId;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Listing references while they are being written.
 *
 * <p>Every reference update writes a temporary file beside its target and
 * renames it over the top, so the directory a listing is walking is the same
 * directory writers are churning. An entry can be enumerated and then be gone
 * before anything can be asked about it.
 *
 * <p>Filtering the temporary names out of a stream afterwards does not survive
 * that, because the filter never runs: reading the attributes of a name that has
 * just been renamed away throws first. Measured on Linux before the fix, four
 * readers against four writers failed on the first or second listing, three runs
 * out of three.
 *
 * <p>What the fix must not do is turn every failure into an empty answer. A name
 * that is gone is an ordinary thing to observe under concurrent updates; a
 * directory that cannot be read is not, and a store that genuinely cannot be
 * listed has to say so. Both halves are pinned below.
 */
class RefEnumerationConcurrencyTest {

    @TempDir
    Path root;

    private FileSystemRefStore refs;

    @BeforeEach
    void setUp() {
        refs = new FileSystemRefStore(root);
    }

    private static ObjectId id(int i) {
        return ObjectId.fromHex(String.format("%040d", i));
    }

    private Path headsRoot() {
        return root.resolve("refs").resolve("heads");
    }

    // ----------------------------------------------------------- concurrency

    @Nested
    @DisplayName("while writers are replacing references")
    class UnderChurn {

        @RepeatedTest(value = 3, name = "run {currentRepetition} of {totalRepetitions}")
        @DisplayName("listing never fails because an entry disappeared")
        void listingSurvivesConcurrentUpdates() throws Exception {
            refs.createBranch("main", id(1));
            for (int i = 0; i < 20; i++) {
                refs.createBranch("feature/" + i, id(i + 2));
            }
            refs.createTag("v1", id(1));

            AtomicReference<Throwable> readerFailure = new AtomicReference<>();
            AtomicReference<Throwable> writerFailure = new AtomicReference<>();
            AtomicInteger listings = new AtomicInteger();

            int writers = 4;
            int readers = 4;
            ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
            CountDownLatch start = new CountDownLatch(1);

            for (int w = 0; w < writers; w++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 300; i++) {
                            refs.updateBranch("main", id(i + 100));
                        }
                    } catch (Throwable thrown) {
                        writerFailure.compareAndSet(null, thrown);
                    }
                });
            }
            for (int r = 0; r < readers; r++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 600; i++) {
                            // All three enumeration paths share one walk, and a
                            // temporary file is written beside whichever
                            // reference is being replaced, so all three are
                            // exposed to the same churn.
                            assertThat(refs.listBranches()).contains("main");
                            refs.listTags();
                            refs.listRemoteRefs();
                            listings.incrementAndGet();
                        }
                    } catch (Throwable thrown) {
                        readerFailure.compareAndSet(null, thrown);
                    }
                });
            }

            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(2, TimeUnit.MINUTES)).isTrue();

            if (readerFailure.get() != null) {
                throw new AssertionError(
                        "a listing failed while references were being replaced",
                        readerFailure.get());
            }
            assertThat(listings.get())
                    .as("the readers actually ran; before the fix they stopped on the first listing")
                    .isEqualTo(readers * 600);

            // Writers are not asserted on: whether a rename over an open
            // reference succeeds is a platform question, characterised
            // elsewhere. What matters here is that reading did not break.
            if (writerFailure.get() != null) {
                System.out.println("  note: a writer failed ("
                        + writerFailure.get().getClass().getSimpleName()
                        + "); that is the platform rename behaviour, not the listing");
            }
        }

        @RepeatedTest(value = 3, name = "run {currentRepetition} of {totalRepetitions}")
        @DisplayName("listing never fails because a branch was deleted underneath it")
        void listingSurvivesConcurrentDeletion() throws Exception {
            for (int i = 0; i < 200; i++) {
                refs.createBranch("doomed/" + i, id(i + 2));
            }
            refs.createBranch("keeper", id(1));

            AtomicReference<Throwable> readerFailure = new AtomicReference<>();
            ExecutorService pool = Executors.newFixedThreadPool(3);
            CountDownLatch start = new CountDownLatch(1);

            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 200; i++) {
                        refs.deleteBranch("doomed/" + i);
                    }
                } catch (Throwable ignored) {
                    // Deleting is not what is under test here.
                }
            });
            for (int r = 0; r < 2; r++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 400; i++) {
                            assertThat(refs.listBranches()).contains("keeper");
                        }
                    } catch (Throwable thrown) {
                        readerFailure.compareAndSet(null, thrown);
                    }
                });
            }

            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(2, TimeUnit.MINUTES)).isTrue();
            if (readerFailure.get() != null) {
                throw new AssertionError(
                        "a listing failed while branches were being deleted", readerFailure.get());
            }
        }
    }

    // ------------------------------------------------------------- filtering

    @Nested
    @DisplayName("what a listing contains")
    class Contents {

        @Test
        @DisplayName("a temporary file left behind is never a branch, a tag or a remote ref")
        void temporaryFilesAreExcluded() throws IOException {
            refs.createBranch("main", id(1));
            refs.createTag("v1", id(1));
            refs.setRemoteRef("origin", "main", id(1));

            // Exactly what an interrupted write leaves behind.
            Files.writeString(headsRoot().resolve(".tmp-ref-1234.tmp"), id(9).toHex() + "\n");
            Files.writeString(root.resolve("refs").resolve("tags").resolve(".tmp-ref-5678.tmp"),
                    id(9).toHex() + "\n");
            Files.writeString(
                    root.resolve("refs").resolve("remotes").resolve("origin")
                            .resolve(".tmp-ref-9012.tmp"),
                    id(9).toHex() + "\n");

            assertThat(refs.listBranches()).containsExactly("main");
            assertThat(refs.listTags()).containsExactly("v1");
            assertThat(refs.listRemoteRefs()).hasSize(1);
        }

        @Test
        @DisplayName("nested names are still listed, in order, with forward slashes")
        void realReferencesRemainVisible() {
            refs.createBranch("main", id(1));
            refs.createBranch("feature/login", id(2));
            refs.createBranch("feature/logout", id(3));
            refs.createTag("release/v1.0", id(1));

            assertThat(refs.listBranches())
                    .containsExactly("feature/login", "feature/logout", "main");
            assertThat(refs.listTags()).containsExactly("release/v1.0");
        }

        @Test
        @DisplayName("an empty store lists nothing rather than failing")
        void emptyStore() {
            assertThat(refs.listBranches()).isEmpty();
            assertThat(refs.listTags()).isEmpty();
            assertThat(refs.listRemoteRefs()).isEmpty();
        }

        @Test
        @EnabledOnOs({OS.LINUX, OS.MAC})
        @DisplayName("a reference reached through a symbolic link is still listed")
        void symbolicLinksRemainVisible(@TempDir Path elsewhere) throws IOException {
            refs.createBranch("main", id(1));

            // The walk reads attributes without following links, so a link
            // reports as a link and not as a regular file. The previous
            // implementation asked Files.isRegularFile, which does follow, and
            // listed it. Whether that is a good idea is a separate question;
            // this change is not the place to decide it, so the answer has to
            // stay the same.
            Path target = Files.writeString(elsewhere.resolve("target"), id(2).toHex() + "\n");
            Files.createSymbolicLink(headsRoot().resolve("linked"), target);

            assertThat(refs.listBranches())
                    .as("listing through a link answers exactly what it answered before")
                    .containsExactly("linked", "main");
        }

        @Test
        @DisplayName("two stores never list each other's references")
        void storesStayIsolated(@TempDir Path other) {
            refs.createBranch("mine", id(1));
            FileSystemRefStore elsewhere = new FileSystemRefStore(other);
            elsewhere.createBranch("theirs", id(2));

            assertThat(refs.listBranches()).containsExactly("mine");
            assertThat(elsewhere.listBranches()).containsExactly("theirs");
        }
    }

    // --------------------------------------------------------- real failures

    @Nested
    @DisplayName("a failure that is not a vanished entry")
    class GenuineFailures {

        @Test
        @EnabledOnOs({OS.LINUX, OS.MAC})
        @DisplayName("is reported, not turned into an empty listing")
        void unreadableDirectoryStillFails() throws IOException {
            refs.createBranch("main", id(1));
            Path heads = headsRoot();
            Set<java.nio.file.attribute.PosixFilePermission> original =
                    Files.getPosixFilePermissions(heads);
            Files.setPosixFilePermissions(heads, Set.of());
            try {
                // A process running as root ignores the permission bits, and
                // then there is no failure to observe. Skip rather than assert
                // something the environment cannot produce.
                Assumptions.assumeFalse(Files.isReadable(heads),
                        "the directory is still readable, so this cannot be tested here");

                assertThatThrownBy(() -> refs.listBranches())
                        .as("an unreadable store must say so instead of reporting no branches")
                        .isInstanceOf(RefException.class)
                        .hasMessageContaining("Could not list branches");
            } finally {
                Files.setPosixFilePermissions(heads, original);
            }
        }
    }
}
