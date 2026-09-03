package com.gitforge.vcs.object;

import com.gitforge.vcs.storage.FileSystemObjectStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The annotated tag object.
 *
 * <p>A tag is content-addressed exactly as every other object is — no parallel
 * hashing, no separate identity scheme — so the tests that matter most are the
 * ones proving the round trip is lossless and the id is a function of the bytes.
 * If either failed, an annotated tag would be a mutable note wearing an object's
 * clothing.
 */
class TagObjectTest {

    @TempDir
    Path storeRoot;

    private static final ObjectId TARGET =
            ObjectId.fromHex("a94a8fe5ccb19ba61c4c0873d391e987982fbbd3");
    private static final ObjectId OTHER =
            ObjectId.fromHex("da39a3ee5e6b4b0d3255bfef95601890afd80709");

    private static final Signature TAGGER = new Signature(
            "Ada Lovelace",
            "ada@example.test",
            Instant.ofEpochSecond(1_700_000_000L),
            ZoneOffset.ofHours(1));

    private static Tag tag(String name, ObjectId target, ObjectType targetType, String message) {
        return new Tag(target, targetType, name, TAGGER, message);
    }

    private FileSystemObjectStore store;

    @BeforeEach
    void setUp() {
        store = new FileSystemObjectStore(storeRoot);
    }

    @Nested
    @DisplayName("identity")
    class Identity {

        @Test
        void theTypeIsTag() {
            assertThat(tag("v1", TARGET, ObjectType.COMMIT, "Release 1\n").type())
                    .isEqualTo(ObjectType.TAG);
        }

        @Test
        void theIdIsTheHashOfTheFramedPayload() {
            Tag subject = tag("v1", TARGET, ObjectType.COMMIT, "Release 1\n");

            ObjectId expected = ObjectFormat.computeId(ObjectType.TAG, subject.payload());

            assertThat(subject.id()).isEqualTo(expected);
        }

        @Test
        void theSameContentAlwaysProducesTheSameId() {
            assertThat(tag("v1", TARGET, ObjectType.COMMIT, "Release 1\n").id())
                    .isEqualTo(tag("v1", TARGET, ObjectType.COMMIT, "Release 1\n").id());
        }

        @Test
        void aDifferentMessageIsADifferentTag() {
            assertThat(tag("v1", TARGET, ObjectType.COMMIT, "One\n").id())
                    .isNotEqualTo(tag("v1", TARGET, ObjectType.COMMIT, "Two\n").id());
        }

        @Test
        void aDifferentNameIsADifferentTag() {
            // The name is inside the hashed bytes, so the same commit tagged twice
            // under different names is two objects rather than one shared one.
            assertThat(tag("v1", TARGET, ObjectType.COMMIT, "Release\n").id())
                    .isNotEqualTo(tag("v2", TARGET, ObjectType.COMMIT, "Release\n").id());
        }

        @Test
        void aDifferentTargetIsADifferentTag() {
            assertThat(tag("v1", TARGET, ObjectType.COMMIT, "Release\n").id())
                    .isNotEqualTo(tag("v1", OTHER, ObjectType.COMMIT, "Release\n").id());
        }

        @Test
        void equalityAndHashingFollowTheId() {
            Tag one = tag("v1", TARGET, ObjectType.COMMIT, "Release\n");
            Tag same = tag("v1", TARGET, ObjectType.COMMIT, "Release\n");

            assertThat(one).isEqualTo(same);
            assertThat(one).hasSameHashCodeAs(same);
        }
    }

    @Nested
    @DisplayName("serialization")
    class Serialization {

        @Test
        void thePayloadHasTheDocumentedShape() {
            String text = new String(
                    tag("v1.0.0", TARGET, ObjectType.COMMIT, "Ship it\n").payload(),
                    StandardCharsets.UTF_8);

            assertThat(text).isEqualTo(
                    "object " + TARGET.toHex() + "\n"
                            + "type commit\n"
                            + "tag v1.0.0\n"
                            + "tagger " + TAGGER.format() + "\n"
                            + "\n"
                            + "Ship it\n");
        }

        @Test
        void aMessageWithoutATrailingNewlineGainsExactlyOne() {
            assertThat(tag("v1", TARGET, ObjectType.COMMIT, "Ship it").message())
                    .isEqualTo("Ship it\n");
        }

        @Test
        void normalisingTheMessageIsIdempotent() {
            // Otherwise re-serializing a parsed tag would change its id.
            Tag once = tag("v1", TARGET, ObjectType.COMMIT, "Ship it");
            Tag twice = tag("v1", TARGET, ObjectType.COMMIT, once.message());

            assertThat(twice.id()).isEqualTo(once.id());
        }

        @Test
        void aMultiLineMessageSurvivesUnchanged() {
            String message = "Release 1.0\n\nWith notes.\nAnd more.\n";

            assertThat(tag("v1", TARGET, ObjectType.COMMIT, message).message()).isEqualTo(message);
        }

        @Test
        void aUnicodeMessageSurvives() {
            String message = "版本 1.0 — released ✅\n";

            assertThat(tag("v1", TARGET, ObjectType.COMMIT, message).message()).isEqualTo(message);
        }
    }

    @Nested
    @DisplayName("round trip through the object store")
    class RoundTrip {

        @Test
        void aTagIsWrittenReadParsedAndVerified() {
            Tag written = tag("v1.0.0", TARGET, ObjectType.COMMIT, "Ship it\n");

            ObjectId id = store.write(written);
            assertThat(id).isEqualTo(written.id());

            VcsObject read = store.read(id).orElseThrow();

            assertThat(read).isInstanceOf(Tag.class);
            Tag parsed = (Tag) read;

            assertThat(parsed.id()).isEqualTo(written.id());
            assertThat(parsed.target()).isEqualTo(TARGET);
            assertThat(parsed.targetType()).isEqualTo(ObjectType.COMMIT);
            assertThat(parsed.name()).isEqualTo("v1.0.0");
            assertThat(parsed.message()).isEqualTo("Ship it\n");
            assertThat(parsed.tagger().name()).isEqualTo("Ada Lovelace");
            assertThat(parsed.payload()).isEqualTo(written.payload());

            // The store re-hashes on read; this passing is that check succeeding.
            store.verify(id);
        }

        @Test
        void aTagPointingAtATagRoundTripsWithItsTypePreserved() {
            Tag inner = tag("inner", TARGET, ObjectType.COMMIT, "Inner\n");
            Tag outer = tag("outer", inner.id(), ObjectType.TAG, "Outer\n");

            store.write(inner);
            store.write(outer);

            Tag readBack = (Tag) store.read(outer.id()).orElseThrow();

            assertThat(readBack.targetType()).isEqualTo(ObjectType.TAG);
            assertThat(readBack.pointsAtATag()).isTrue();
            assertThat(readBack.target()).isEqualTo(inner.id());
        }

        @Test
        void aTagMayNameATreeOrABlobNotOnlyACommit() {
            Tag treeTag = tag("t", TARGET, ObjectType.TREE, "A tree\n");
            Tag blobTag = tag("b", TARGET, ObjectType.BLOB, "A blob\n");

            store.write(treeTag);
            store.write(blobTag);

            assertThat(((Tag) store.read(treeTag.id()).orElseThrow()).targetType())
                    .isEqualTo(ObjectType.TREE);
            assertThat(((Tag) store.read(blobTag.id()).orElseThrow()).targetType())
                    .isEqualTo(ObjectType.BLOB);
        }
    }

    @Nested
    @DisplayName("corruption is reported, not absorbed")
    class Corruption {

        private Tag parseOf(String payload) {
            return (Tag) ObjectFormat.parse(
                    ObjectFormat.frame(ObjectType.TAG, payload.getBytes(StandardCharsets.UTF_8)));
        }

        @Test
        void aPayloadWithNoBlankLineIsRejected() {
            assertThatThrownBy(() -> parseOf("object " + TARGET.toHex() + "\ntype commit\n"))
                    .isInstanceOf(CorruptObjectException.class)
                    .hasMessageContaining("blank line");
        }

        @Test
        void aMissingTargetIsRejected() {
            assertThatThrownBy(() -> parseOf(
                    "type commit\ntag v1\ntagger " + TAGGER.format() + "\n\nm\n"))
                    .isInstanceOf(CorruptObjectException.class)
                    .hasMessageContaining("does not reference a target");
        }

        @Test
        void aMissingTargetTypeIsRejected() {
            assertThatThrownBy(() -> parseOf(
                    "object " + TARGET.toHex() + "\ntag v1\ntagger " + TAGGER.format() + "\n\nm\n"))
                    .isInstanceOf(CorruptObjectException.class)
                    .hasMessageContaining("target's type");
        }

        @Test
        void aMissingNameIsRejected() {
            assertThatThrownBy(() -> parseOf(
                    "object " + TARGET.toHex() + "\ntype commit\ntagger " + TAGGER.format() + "\n\nm\n"))
                    .isInstanceOf(CorruptObjectException.class)
                    .hasMessageContaining("no name");
        }

        @Test
        void aMissingTaggerIsRejected() {
            assertThatThrownBy(() -> parseOf(
                    "object " + TARGET.toHex() + "\ntype commit\ntag v1\n\nm\n"))
                    .isInstanceOf(CorruptObjectException.class)
                    .hasMessageContaining("no tagger");
        }

        @Test
        void anUnknownTargetTypeIsRejected() {
            assertThatThrownBy(() -> parseOf(
                    "object " + TARGET.toHex() + "\ntype sandwich\ntag v1\ntagger "
                            + TAGGER.format() + "\n\nm\n"))
                    .isInstanceOf(CorruptObjectException.class)
                    .hasMessageContaining("unknown target type");
        }

        @Test
        void anInvalidTargetIdIsRejected() {
            assertThatThrownBy(() -> parseOf(
                    "object nothex\ntype commit\ntag v1\ntagger " + TAGGER.format() + "\n\nm\n"))
                    .isInstanceOf(CorruptObjectException.class)
                    .hasMessageContaining("invalid target id");
        }

        @Test
        void anUnrecognisedHeaderIsRejected() {
            assertThatThrownBy(() -> parseOf(
                    "object " + TARGET.toHex() + "\ntype commit\ntag v1\nsigned yes\ntagger "
                            + TAGGER.format() + "\n\nm\n"))
                    .isInstanceOf(CorruptObjectException.class)
                    .hasMessageContaining("unrecognised header");
        }

        @Test
        void twoTargetsAreRejected() {
            assertThatThrownBy(() -> parseOf(
                    "object " + TARGET.toHex() + "\nobject " + OTHER.toHex()
                            + "\ntype commit\ntag v1\ntagger " + TAGGER.format() + "\n\nm\n"))
                    .isInstanceOf(CorruptObjectException.class)
                    .hasMessageContaining("more than one target");
        }
    }

    @Nested
    @DisplayName("construction rules")
    class Construction {

        @Test
        void aNullTargetIsRejected() {
            assertThatThrownBy(() -> new Tag(null, ObjectType.COMMIT, "v1", TAGGER, "m"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("target object");
        }

        @Test
        void aNullTargetTypeIsRejected() {
            assertThatThrownBy(() -> new Tag(TARGET, null, "v1", TAGGER, "m"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("type of its target");
        }

        @Test
        void aBlankNameIsRejected() {
            assertThatThrownBy(() -> new Tag(TARGET, ObjectType.COMMIT, "  ", TAGGER, "m"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must have a name");
        }

        @Test
        void aNameContainingANewlineIsRejected() {
            // It would forge a header line when serialized, so this is a format
            // rule rather than a naming preference.
            assertThatThrownBy(() ->
                    new Tag(TARGET, ObjectType.COMMIT, "v1\ntagger evil", TAGGER, "m"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("newline");
        }

        @Test
        void aNullTaggerIsRejected() {
            assertThatThrownBy(() -> new Tag(TARGET, ObjectType.COMMIT, "v1", null, "m"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tagger");
        }

        @Test
        void aNullMessageIsRejected() {
            assertThatThrownBy(() -> new Tag(TARGET, ObjectType.COMMIT, "v1", TAGGER, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("message");
        }
    }

    @Nested
    @DisplayName("type-specific reads stay safe now that a fourth type exists")
    class TypedReads {

        /**
         * The compiler could not help here. {@code readBlob}, {@code readTree} and
         * {@code readCommit} test with {@code instanceof} rather than an exhaustive
         * switch, so adding a fourth object type produced no build error at these
         * three sites. They are checked by hand instead.
         */
        @Test
        void readingATagAsACommitIsRefused() {
            ObjectId id = store.write(tag("v1", TARGET, ObjectType.COMMIT, "m\n"));

            assertThatThrownBy(() -> store.readCommit(id))
                    .isInstanceOf(CorruptObjectException.class)
                    .hasMessageContaining("is a tag, not a commit");
        }

        @Test
        void readingATagAsATreeIsRefused() {
            ObjectId id = store.write(tag("v1", TARGET, ObjectType.COMMIT, "m\n"));

            assertThatThrownBy(() -> store.readTree(id))
                    .isInstanceOf(CorruptObjectException.class)
                    .hasMessageContaining("is a tag, not a tree");
        }

        @Test
        void readingATagAsABlobIsRefused() {
            ObjectId id = store.write(tag("v1", TARGET, ObjectType.COMMIT, "m\n"));

            assertThatThrownBy(() -> store.readBlob(id))
                    .isInstanceOf(CorruptObjectException.class)
                    .hasMessageContaining("is a tag, not a blob");
        }
    }

    @Nested
    @DisplayName("the type is registered everywhere it must be")
    class TypeRegistration {

        @Test
        void theHeaderLiteralIsTag() {
            assertThat(ObjectType.TAG.header()).isEqualTo("tag");
        }

        @Test
        void theHeaderParsesBackToTheType() {
            assertThat(ObjectType.fromHeader("tag")).isEqualTo(ObjectType.TAG);
        }

        @Test
        void theSealedHierarchyPermitsExactlyFourKinds() {
            assertThat(VcsObject.class.getPermittedSubclasses())
                    .containsExactlyInAnyOrder(
                            Blob.class, Tree.class, Commit.class, Tag.class);
        }
    }
}
