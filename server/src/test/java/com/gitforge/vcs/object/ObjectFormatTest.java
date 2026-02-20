package com.gitforge.vcs.object;

import com.gitforge.vcs.GoldenVectors;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObjectFormatTest {

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void framesAsTypeSpaceLengthNulPayload() {
        assertThat(ObjectFormat.frame(ObjectType.BLOB, ascii("hello world")))
                .isEqualTo(ascii("blob 11\0hello world"));
    }

    @Test
    void framesAnEmptyPayloadWithAZeroLength() {
        assertThat(ObjectFormat.frame(ObjectType.BLOB, new byte[0])).isEqualTo(ascii("blob 0\0"));
        assertThat(ObjectFormat.frame(ObjectType.TREE, new byte[0])).isEqualTo(ascii("tree 0\0"));
    }

    @Test
    void identityCoversTheHeaderNotJustThePayload() {
        byte[] payload = ascii("hello world");

        // Same bytes under two type headers must not collide.
        assertThat(ObjectFormat.computeId(ObjectType.BLOB, payload))
                .isNotEqualTo(ObjectFormat.computeId(ObjectType.TREE, payload));

        // And the id is not merely the digest of the payload alone.
        assertThat(ObjectFormat.computeId(ObjectType.BLOB, payload))
                .isNotEqualTo(ObjectId.ofContent(payload));
    }

    @Test
    void computedIdMatchesTheIndependentImplementation() {
        assertThat(ObjectFormat.computeId(ObjectType.BLOB, ascii("hello world")).toHex())
                .isEqualTo(GoldenVectors.BLOB_HELLO_WORLD);
    }

    @Test
    void blobRoundTrips() {
        Blob original = new Blob(new byte[]{0, 1, 2, (byte) 0xFF});

        VcsObject parsed = ObjectFormat.parse(ObjectFormat.serialize(original));

        assertThat(parsed).isInstanceOf(Blob.class);
        assertThat(parsed.payload()).isEqualTo(original.payload());
        assertThat(parsed.id()).isEqualTo(original.id());
    }

    @Test
    void treeRoundTrips() {
        Tree original = new Tree(List.of(
                new TreeEntry(FileMode.REGULAR_FILE, "README.md", ObjectId.fromHex(GoldenVectors.BLOB_README)),
                new TreeEntry(FileMode.DIRECTORY, "src", ObjectId.fromHex(GoldenVectors.TREE_SRC))));

        VcsObject parsed = ObjectFormat.parse(ObjectFormat.serialize(original));

        assertThat(parsed.id()).isEqualTo(original.id());
    }

    @Test
    void payloadContainingNulBytesSurvivesParsing() {
        // The header's NUL terminator must not be confused with NULs in content.
        Blob original = new Blob(new byte[]{0, 0, 0});

        assertThat(ObjectFormat.parse(ObjectFormat.serialize(original)).payload())
                .isEqualTo(new byte[]{0, 0, 0});
    }

    @Test
    void rejectsAHeaderWithoutASpace() {
        assertThatThrownBy(() -> ObjectFormat.parse(ascii("blob11\0data")))
                .isInstanceOf(CorruptObjectException.class)
                .hasMessageContaining("no space");
    }

    @Test
    void rejectsAHeaderWithoutANulTerminator() {
        assertThatThrownBy(() -> ObjectFormat.parse(ascii("blob 11 hello world")))
                .isInstanceOf(CorruptObjectException.class)
                .hasMessageContaining("NUL");
    }

    @Test
    void rejectsAnUnknownType() {
        assertThatThrownBy(() -> ObjectFormat.parse(ascii("widget 4\0data")))
                .isInstanceOf(CorruptObjectException.class)
                .hasMessageContaining("unknown type");
    }

    @Test
    void rejectsANonNumericLength() {
        assertThatThrownBy(() -> ObjectFormat.parse(ascii("blob xx\0data")))
                .isInstanceOf(CorruptObjectException.class)
                .hasMessageContaining("non-numeric length");
    }

    @Test
    void rejectsALengthThatDisagreesWithThePayload() {
        // Declares 99 bytes but carries 4: the length is a checkable property.
        assertThatThrownBy(() -> ObjectFormat.parse(ascii("blob 99\0data")))
                .isInstanceOf(CorruptObjectException.class)
                .hasMessageContaining("declares 99 payload bytes but 4 are present");
    }

    @Test
    void rejectsATruncatedTreeEntry() {
        byte[] truncated = ascii("100644 file\0short");

        assertThatThrownBy(() -> ObjectFormat.parse(ObjectFormat.frame(ObjectType.TREE, truncated)))
                .isInstanceOf(CorruptObjectException.class)
                .hasMessageContaining("truncated");
    }

    @Test
    void rejectsATreeEntryWithAnUnsupportedMode() {
        byte[] payload = new byte[ascii("999999 file").length + 1 + ObjectId.LENGTH];
        byte[] prefix = ascii("999999 file");
        System.arraycopy(prefix, 0, payload, 0, prefix.length);

        assertThatThrownBy(() -> ObjectFormat.parse(ObjectFormat.frame(ObjectType.TREE, payload)))
                .isInstanceOf(CorruptObjectException.class)
                .hasMessageContaining("unsupported mode");
    }
}
