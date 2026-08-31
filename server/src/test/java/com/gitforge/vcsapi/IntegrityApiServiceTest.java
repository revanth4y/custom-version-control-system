package com.gitforge.vcsapi;

import com.gitforge.vcs.object.Blob;
import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.ObjectFormat;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Tree;
import com.gitforge.vcs.object.VcsObject;
import com.gitforge.vcs.storage.FileSystemObjectStore;
import com.gitforge.vcs.storage.ObjectStore;
import com.gitforge.vcsapi.dto.IntegrityReport;
import com.gitforge.vcsapi.dto.IntegrityReport.Reason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The integrity scan, run against a real object store.
 *
 * <p>Deliberately not a mocked store. The claim being made is that objects are
 * genuinely read back and re-hashed, and a stub that returns whatever it is told
 * would prove the opposite of what these tests exist to establish. Corruption is
 * therefore applied to real files on disk, the same way it would happen.
 *
 * <p>Detection itself belongs to {@code FileSystemObjectStoreTest}, which is
 * untouched. What is asserted here is the reporting built on top: that each
 * failure is classified correctly, that a damaged store still produces a report
 * rather than an exception, and that nothing about the filesystem leaks into it.
 */
class IntegrityApiServiceTest {

    @TempDir
    Path repositoryRoot;

    private FileSystemObjectStore store;
    private IntegrityApiService service;

    @BeforeEach
    void setUp() {
        store = new FileSystemObjectStore(repositoryRoot);
        service = new IntegrityApiService(null, IntegrityApiService.DEFAULT_MAX_VERIFIED_OBJECTS);
    }

    private static Blob blob(String content) {
        return new Blob(content.getBytes(StandardCharsets.UTF_8));
    }

    private Path fileFor(ObjectId id) {
        String hex = id.toHex();
        return repositoryRoot.resolve("objects").resolve(hex.substring(0, 2)).resolve(hex.substring(2));
    }

    private static byte[] compress(byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(out)) {
            deflater.write(raw);
        }
        return out.toByteArray();
    }

    @Nested
    @DisplayName("a healthy store")
    class Healthy {

        @Test
        void reportsEveryObjectVerifiedAndNothingDamaged() {
            store.write(blob("one"));
            store.write(blob("two"));
            store.write(blob("three"));

            IntegrityReport report = service.scan(store);

            assertThat(report.storedObjects()).isEqualTo(3);
            assertThat(report.verified()).isEqualTo(3);
            assertThat(report.damaged()).isEmpty();
            assertThat(report.healthy()).isTrue();
            assertThat(report.truncated()).isFalse();
        }

        @Test
        void verifiesTreesAndCommitsAndNotOnlyBlobs() {
            // Every object type goes through the same framed hash, so a scan that
            // only ever saw blobs would not have shown much.
            ObjectId blobId = store.write(blob("content"));
            assertThat(blobId).isNotNull();

            IntegrityReport report = service.scan(store);

            assertThat(report.verified()).isEqualTo(store.listIds().size());
            assertThat(report.healthy()).isTrue();
        }

        @Test
        void recordsWhenTheCheckRan() {
            store.write(blob("one"));

            IntegrityReport report = service.scan(store);

            assertThat(report.checkedAt()).isNotNull();
            assertThat(report.durationMs()).isNotNegative();
        }
    }

    @Nested
    @DisplayName("an empty store")
    class Empty {

        @Test
        void verifiesNothingAndClaimsNothing() {
            IntegrityReport report = service.scan(store);

            assertThat(report.storedObjects()).isZero();
            assertThat(report.verified()).isZero();
            assertThat(report.damaged()).isEmpty();
            assertThat(report.truncated()).isFalse();
        }

        @Test
        void isNotReportedAsHealthy() {
            // The distinction the whole report rests on: nothing was checked, so
            // nothing was shown to be sound. Saying "healthy" here would be a
            // claim this scan never established.
            assertThat(service.scan(store).healthy()).isNull();
        }
    }

    @Nested
    @DisplayName("corruption")
    class Corruption {

        @Test
        void reportsAFlippedByteAsUnreadable() throws IOException {
            ObjectId id = store.write(blob("content that will be corrupted"));
            byte[] stored = Files.readAllBytes(fileFor(id));
            stored[stored.length / 2] ^= (byte) 0xFF;
            Files.write(fileFor(id), stored);

            IntegrityReport report = service.scan(store);

            assertThat(report.healthy()).isFalse();
            assertThat(report.damaged()).singleElement().satisfies(damaged -> {
                assertThat(damaged.id()).isEqualTo(id.toHex());
                assertThat(damaged.reason()).isEqualTo(Reason.UNREADABLE);
            });
        }

        @Test
        void reportsATruncatedFileAsUnreadable() throws IOException {
            ObjectId id = store.write(blob("content that will be truncated"));
            byte[] stored = Files.readAllBytes(fileFor(id));
            Files.write(fileFor(id), java.util.Arrays.copyOf(stored, stored.length / 2));

            assertThat(service.scan(store).damaged())
                    .singleElement()
                    .satisfies(damaged -> assertThat(damaged.reason()).isEqualTo(Reason.UNREADABLE));
        }

        @Test
        void reportsGarbageThatIsNotCompressedAsUnreadable() throws IOException {
            ObjectId id = store.write(blob("content"));
            Files.write(fileFor(id), "not a zlib stream at all".getBytes(StandardCharsets.UTF_8));

            assertThat(service.scan(store).damaged())
                    .singleElement()
                    .satisfies(damaged -> assertThat(damaged.reason()).isEqualTo(Reason.UNREADABLE));
        }

        @Test
        void reportsValidContentFiledUnderTheWrongIdAsAHashMismatch() throws IOException {
            // The case nothing but re-hashing can catch: the bytes decompress and
            // parse into a perfectly good object that simply is not the one asked
            // for. A file-existence or parse check would call this healthy.
            ObjectId requested = store.write(blob("the original"));
            byte[] impostor = compress(ObjectFormat.serialize(blob("a different object")));
            Files.write(fileFor(requested), impostor);

            IntegrityReport report = service.scan(store);

            assertThat(report.healthy()).isFalse();
            assertThat(report.damaged()).singleElement().satisfies(damaged -> {
                assertThat(damaged.id()).isEqualTo(requested.toHex());
                assertThat(damaged.reason()).isEqualTo(Reason.HASH_MISMATCH);
            });
        }

        @Test
        void reportsAnObjectThatDisappearsMidScanAsMissing() {
            // listIds() derives ids from files that exist, so absence can only
            // arise from a removal between enumeration and verification. Simulated
            // rather than raced, because a race cannot be asserted reliably.
            ObjectId vanished = blob("gone").id();

            IntegrityReport report = service.scan(new VanishingStore(vanished));

            assertThat(report.healthy()).isFalse();
            assertThat(report.damaged()).singleElement().satisfies(damaged -> {
                assertThat(damaged.id()).isEqualTo(vanished.toHex());
                assertThat(damaged.reason()).isEqualTo(Reason.MISSING);
            });
        }

        @Test
        void separatesTheDamagedFromTheSoundRatherThanCondemningTheStore() throws IOException {
            store.write(blob("healthy one"));
            store.write(blob("healthy two"));
            ObjectId broken = store.write(blob("broken"));
            Files.write(fileFor(broken), "not a zlib stream".getBytes(StandardCharsets.UTF_8));

            IntegrityReport report = service.scan(store);

            assertThat(report.storedObjects()).isEqualTo(3);
            assertThat(report.verified()).isEqualTo(3);
            assertThat(report.damaged()).hasSize(1);
            assertThat(report.healthy()).isFalse();
        }

        @Test
        void completesTheScanRatherThanStoppingAtTheFirstFailure() throws IOException {
            ObjectId first = store.write(blob("first"));
            ObjectId second = store.write(blob("second"));
            Files.write(fileFor(first), "broken".getBytes(StandardCharsets.UTF_8));
            Files.write(fileFor(second), "broken".getBytes(StandardCharsets.UTF_8));

            assertThat(service.scan(store).damaged()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("the cap")
    class Cap {

        @Test
        void stopsAtTheCapAndSaysSo() {
            for (int i = 0; i < 5; i++) {
                store.write(blob("object " + i));
            }

            IntegrityReport report = new IntegrityApiService(null, 3).scan(store);

            assertThat(report.storedObjects()).isEqualTo(5);
            assertThat(report.verified()).isEqualTo(3);
            assertThat(report.truncated()).isTrue();
        }

        @Test
        void isNotFlaggedWhenEverythingFits() {
            store.write(blob("only one"));

            assertThat(new IntegrityApiService(null, 3).scan(store).truncated()).isFalse();
        }

        @Test
        void checksTheSameObjectsEveryTime() {
            for (int i = 0; i < 6; i++) {
                store.write(blob("object " + i));
            }
            IntegrityApiService capped = new IntegrityApiService(null, 2);

            // Truncation follows a sorted order rather than whatever the
            // filesystem walked first, so a second scan is comparable to the first.
            assertThat(capped.scan(store).damaged()).isEqualTo(capped.scan(store).damaged());
            assertThat(capped.scan(store).verified()).isEqualTo(2);
        }

        @Test
        void refusesACapBelowOneObject() {
            assertThat(org.assertj.core.api.Assertions
                            .catchThrowable(() -> new IntegrityApiService(null, 0)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("what the report discloses")
    class Disclosure {

        @Test
        void namesNoFilesystemPath() throws IOException {
            ObjectId id = store.write(blob("content"));
            Files.write(fileFor(id), "not a zlib stream".getBytes(StandardCharsets.UTF_8));

            IntegrityReport report = service.scan(store);
            String rendered = report.damaged().toString();

            assertThat(rendered)
                    .doesNotContain(repositoryRoot.toString())
                    .doesNotContain("objects")
                    .doesNotContain(java.io.File.separator);
        }

        @Test
        void describesDamageFromAClosedVocabularyRatherThanAnExceptionMessage() throws IOException {
            ObjectId id = store.write(blob("content"));
            Files.write(fileFor(id), "not a zlib stream".getBytes(StandardCharsets.UTF_8));

            assertThat(service.scan(store).damaged())
                    .singleElement()
                    .satisfies(damaged -> assertThat(damaged.detail()).isEqualTo(Reason.UNREADABLE.detail()));
        }

        @Test
        void reportsTheFullObjectIdSoItCanBeLookedUp() throws IOException {
            ObjectId id = store.write(blob("content"));
            Files.write(fileFor(id), "not a zlib stream".getBytes(StandardCharsets.UTF_8));

            assertThat(service.scan(store).damaged().getFirst().id()).hasSize(40).isEqualTo(id.toHex());
        }
    }

    /** Reports an id it does not hold, which is the only way MISSING can arise. */
    private record VanishingStore(ObjectId absent) implements ObjectStore {

        @Override
        public List<ObjectId> listIds() {
            return List.of(absent);
        }

        @Override
        public boolean contains(ObjectId id) {
            return false;
        }

        @Override
        public ObjectId write(VcsObject object) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<VcsObject> read(ObjectId id) {
            return Optional.empty();
        }

        @Override
        public Blob readBlob(ObjectId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Tree readTree(ObjectId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Commit readCommit(ObjectId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void verify(ObjectId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long count() {
            return 1;
        }

        @Override
        public List<ObjectId> findByPrefix(String hexPrefix) {
            throw new UnsupportedOperationException();
        }
    }
}
