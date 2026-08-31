package com.gitforge.vcs.storage;

import com.gitforge.vcs.object.Blob;
import com.gitforge.vcs.object.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Finding objects by the start of their id.
 *
 * <p>The interesting cases are the ones a naive string match gets wrong: a
 * prefix that stops inside the shard directory, a prefix that spans it, two
 * objects that genuinely collide, and the same prefix typed in capitals.
 */
class ObjectStorePrefixTest {

    @TempDir
    Path repositoryRoot;

    private FileSystemObjectStore store;

    @BeforeEach
    void setUp() {
        store = new FileSystemObjectStore(repositoryRoot);
    }

    /** Writes a blob and returns its id, so tests can search for what is really there. */
    private ObjectId write(String content) {
        return store.write(new Blob(content.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Content whose id starts with {@code prefix}, found by trying candidates.
     *
     * <p>Ids cannot be chosen, so a test that needs a collision has to go
     * looking for one. Four hex characters is a one-in-65,536 chance per
     * candidate, which is found quickly enough and, more to the point,
     * deterministically: the same search runs the same way every time.
     */
    private ObjectId writeStartingWith(String prefix) {
        for (int i = 0; i < 1_000_000; i++) {
            Blob candidate = new Blob(("collision-candidate-" + i).getBytes(StandardCharsets.UTF_8));
            if (candidate.id().toHex().startsWith(prefix)) {
                return store.write(candidate);
            }
        }
        throw new IllegalStateException("No content found with prefix " + prefix);
    }

    @Nested
    @DisplayName("finding one object")
    class One {

        @Test
        void findsAnObjectByTheFirstFourCharacters() {
            ObjectId id = write("four");

            assertThat(store.findByPrefix(id.abbreviate(4))).containsExactly(id);
        }

        @Test
        void findsAnObjectByTheSevenCharactersTheInterfaceShows() {
            ObjectId id = write("seven");

            assertThat(store.findByPrefix(id.abbreviate(7))).containsExactly(id);
        }

        @Test
        void findsAnObjectByThirtyNineCharacters() {
            ObjectId id = write("thirty-nine");

            assertThat(store.findByPrefix(id.abbreviate(39))).containsExactly(id);
        }

        @Test
        void findsAnObjectByItsWholeId() {
            // A full id is a prefix of itself. Callers reach here only after an
            // exact lookup has already failed, but the store should not care.
            ObjectId id = write("whole");

            assertThat(store.findByPrefix(id.toHex())).containsExactly(id);
        }

        @Test
        void acceptsThePrefixInCapitals() {
            /* Ids are filed in lower case, so an upper-case prefix would match
               nothing at all — which reads as "no such object" when the caller
               simply typed it differently. */
            ObjectId id = write("capitals");
            String upper = id.abbreviate(7).toUpperCase(Locale.ROOT);

            assertThat(store.findByPrefix(upper)).containsExactly(id);
        }
    }

    @Nested
    @DisplayName("finding nothing")
    class None {

        @Test
        void anUnknownPrefixMatchesNothing() {
            write("something");

            // Every id is present or absent in full; this one is absent.
            assertThat(store.findByPrefix("ffffffff")).isEmpty();
        }

        @Test
        void anEmptyStoreMatchesNothing() {
            assertThat(store.findByPrefix("abcd")).isEmpty();
        }

        @Test
        void aPrefixWhoseShardDirectoryDoesNotExistMatchesNothing() {
            // The shard is the first two characters; if no object was ever filed
            // under it the directory is simply not there.
            ObjectId id = write("shard");
            String otherShard = id.toHex().startsWith("00") ? "11" : "00";

            assertThat(store.findByPrefix(otherShard + "abcd")).isEmpty();
        }
    }

    @Nested
    @DisplayName("ambiguity")
    class Ambiguity {

        @Test
        void reportsEveryCollidingObject() {
            ObjectId first = write("ambiguity-anchor");
            String shared = first.abbreviate(4);
            ObjectId second = writeStartingWith(shared);

            assertThat(second).isNotEqualTo(first);
            assertThat(store.findByPrefix(shared)).containsExactlyInAnyOrder(first, second);
        }

        @Test
        void ordersCandidatesTheSameWayEveryTime() {
            // An ambiguous answer names its candidates to the caller. A
            // directory listing does not promise an order, so the store imposes
            // one rather than reporting the same collision differently twice.
            ObjectId first = write("ordering-anchor");
            String shared = first.abbreviate(4);
            writeStartingWith(shared);

            List<ObjectId> once = store.findByPrefix(shared);
            List<ObjectId> twice = store.findByPrefix(shared);

            assertThat(once).isEqualTo(twice);
            assertThat(once).isSortedAccordingTo(Comparator.comparing(ObjectId::toHex));
        }

        @Test
        void lengtheningThePrefixSeparatesThem() {
            // The advice the error gives has to actually work.
            ObjectId first = write("lengthen-anchor");
            String shared = first.abbreviate(4);
            writeStartingWith(shared);

            assertThat(store.findByPrefix(shared)).hasSizeGreaterThan(1);
            assertThat(store.findByPrefix(first.toHex())).containsExactly(first);
        }
    }

    @Nested
    @DisplayName("rejected input")
    class Rejected {

        @Test
        void refusesAPrefixShorterThanTheMinimum() {
            assertThatThrownBy(() -> store.findByPrefix("abc"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void refusesNonHexadecimalInput() {
            assertThatThrownBy(() -> store.findByPrefix("zzzz"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.findByPrefix("main"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void refusesSomethingLongerThanAnId() {
            assertThatThrownBy(() -> store.findByPrefix("a".repeat(41)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void refusesNull() {
            assertThatThrownBy(() -> store.findByPrefix(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("what it searches")
    class Scope {

        @Test
        void findsBlobsTreesAndCommitsAlike() {
            /* The store holds objects, not commits. A prefix that names a blob
               resolves to that blob here; deciding it is not a commit belongs to
               the caller that wanted one. */
            ObjectId blob = write("a blob");

            assertThat(store.findByPrefix(blob.abbreviate(6))).containsExactly(blob);
        }

        @Test
        void ignoresPartiallyWrittenTemporaryFiles() throws Exception {
            // Writes land through a temporary file in the destination directory.
            // One caught mid-flight is not an object and must not be offered.
            ObjectId id = write("temporary");
            Path shard = repositoryRoot.resolve("objects").resolve(id.toHex().substring(0, 2));
            Files.createFile(shard.resolve(".tmp-not-an-object"));

            assertThat(store.findByPrefix(id.abbreviate(4))).containsExactly(id);
        }
    }
}
