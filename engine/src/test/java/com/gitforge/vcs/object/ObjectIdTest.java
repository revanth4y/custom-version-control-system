package com.gitforge.vcs.object;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObjectIdTest {

    private static final String HEX = "95d09f2b10159347eece71399a7e2e907ea3df4f";

    @Test
    void hexRoundTripsThroughBytes() {
        ObjectId id = ObjectId.fromHex(HEX);

        assertThat(id.toHex()).isEqualTo(HEX);
        assertThat(ObjectId.fromBytes(id.toBytes())).isEqualTo(id);
    }

    @Test
    void hexParsingIsCaseInsensitive() {
        assertThat(ObjectId.fromHex(HEX.toUpperCase())).isEqualTo(ObjectId.fromHex(HEX));
    }

    @Test
    void equalDigestsAreEqualIdsRegardlessOfInstance() {
        ObjectId first = ObjectId.fromHex(HEX);
        ObjectId second = ObjectId.fromBytes(first.toBytes());

        assertThat(first).isEqualTo(second).isNotSameAs(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
    }

    @Test
    void worksAsAKeyInHashBasedCollections() {
        // The graph traversals in later phases depend on this.
        ObjectId id = ObjectId.fromHex(HEX);
        HashSet<ObjectId> visited = new HashSet<>();

        assertThat(visited.add(id)).isTrue();
        assertThat(visited.add(ObjectId.fromHex(HEX))).isFalse();

        HashMap<ObjectId, String> map = new HashMap<>();
        map.put(id, "value");
        assertThat(map).containsEntry(ObjectId.fromHex(HEX), "value");
    }

    @Test
    void ordersByUnsignedByteValue() {
        ObjectId low = ObjectId.fromHex("00".repeat(20));
        ObjectId mid = ObjectId.fromHex("7f".repeat(20));
        // Signed byte comparison would sort 0xFF below 0x00 and break this.
        ObjectId high = ObjectId.fromHex("ff".repeat(20));

        assertThat(List.of(high, low, mid).stream().sorted().toList())
                .containsExactly(low, mid, high);
    }

    @Test
    void abbreviatesToLeadingHexCharacters() {
        ObjectId id = ObjectId.fromHex(HEX);

        assertThat(id.abbreviate(7)).isEqualTo("95d09f2");
        assertThat(id.abbreviate(40)).isEqualTo(HEX);
    }

    @Test
    void rejectsAnOutOfRangeAbbreviation() {
        ObjectId id = ObjectId.fromHex(HEX);

        assertThatThrownBy(() -> id.abbreviate(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> id.abbreviate(41)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsWrongLengthInput() {
        assertThatThrownBy(() -> ObjectId.fromBytes(new byte[19]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be 20 bytes");

        assertThatThrownBy(() -> ObjectId.fromHex("abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("40 characters");
    }

    @Test
    void rejectsNonHexadecimalInput() {
        assertThatThrownBy(() -> ObjectId.fromHex("z".repeat(40)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-hexadecimal");
    }

    @Test
    void rejectsNullInput() {
        assertThatThrownBy(() -> ObjectId.fromBytes(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ObjectId.fromHex(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isImmutableAgainstCallerHeldArrays() {
        byte[] source = ObjectId.fromHex(HEX).toBytes();
        ObjectId id = ObjectId.fromBytes(source);

        // Mutating the array the id was built from must not change the id.
        source[0] = (byte) 0xFF;
        assertThat(id.toHex()).isEqualTo(HEX);

        // Nor may mutating the array the id hands out.
        byte[] exposed = id.toBytes();
        exposed[0] = (byte) 0xFF;
        assertThat(id.toHex()).isEqualTo(HEX);
    }

    @Test
    void contentHashingMatchesTheRawDigest() {
        // "abc" hashed directly, with no object header.
        assertThat(ObjectId.ofContent("abc".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).toHex())
                .isEqualTo("a9993e364706816aba3e25717850c26c9cd0d89d");
    }
}
