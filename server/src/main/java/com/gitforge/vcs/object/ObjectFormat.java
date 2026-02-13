package com.gitforge.vcs.object;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The canonical byte representation of an object, and the definition of its id.
 *
 * <p>An object is framed as:
 *
 * <pre>
 *   &lt;type&gt; &lt;payload-length&gt;\0&lt;payload&gt;
 * </pre>
 *
 * <p>for example {@code blob 11\0hello world}. The SHA-1 is taken over that
 * whole sequence, header included — not over the payload alone, and never over
 * the compressed form used on disk.
 *
 * <p>Including the type and length in the hashed bytes is what stops a blob and
 * a tree with coincidentally identical payloads from colliding, and makes the
 * declared length a checkable property of every object read back.
 */
public final class ObjectFormat {

    private static final byte SPACE = ' ';
    private static final byte NUL = 0;

    private ObjectFormat() {
    }

    /** Frames a payload with its header, producing the bytes that get hashed. */
    public static byte[] frame(ObjectType type, byte[] payload) {
        byte[] header = (type.header() + " " + payload.length).getBytes(StandardCharsets.US_ASCII);

        byte[] framed = new byte[header.length + 1 + payload.length];
        System.arraycopy(header, 0, framed, 0, header.length);
        framed[header.length] = NUL;
        System.arraycopy(payload, 0, framed, header.length + 1, payload.length);
        return framed;
    }

    /** The full canonical representation of {@code object}. */
    public static byte[] serialize(VcsObject object) {
        return frame(object.type(), object.payload());
    }

    /** Computes an object's id from its type and payload. */
    public static ObjectId computeId(ObjectType type, byte[] payload) {
        return ObjectId.ofContent(frame(type, payload));
    }

    /**
     * Reconstructs an object from its canonical representation.
     *
     * @throws CorruptObjectException if the header is malformed, the type is
     *     unknown, or the declared length disagrees with the payload
     */
    public static VcsObject parse(byte[] framed) {
        int space = indexOf(framed, SPACE, 0);
        if (space < 0) {
            throw new CorruptObjectException("Object header has no space separating type from length");
        }
        int nul = indexOf(framed, NUL, space + 1);
        if (nul < 0) {
            throw new CorruptObjectException("Object header is not terminated by a NUL byte");
        }

        String typeText = new String(framed, 0, space, StandardCharsets.US_ASCII);
        String lengthText = new String(framed, space + 1, nul - space - 1, StandardCharsets.US_ASCII);

        ObjectType type;
        try {
            type = ObjectType.fromHeader(typeText);
        } catch (IllegalArgumentException ex) {
            throw new CorruptObjectException("Object header declares an unknown type: " + typeText, ex);
        }

        int declaredLength;
        try {
            declaredLength = Integer.parseInt(lengthText);
        } catch (NumberFormatException ex) {
            throw new CorruptObjectException("Object header declares a non-numeric length: " + lengthText, ex);
        }
        if (declaredLength < 0) {
            throw new CorruptObjectException("Object header declares a negative length: " + declaredLength);
        }

        int actualLength = framed.length - nul - 1;
        if (actualLength != declaredLength) {
            throw new CorruptObjectException(
                    "Object header declares " + declaredLength + " payload bytes but " + actualLength + " are present");
        }

        byte[] payload = new byte[declaredLength];
        System.arraycopy(framed, nul + 1, payload, 0, declaredLength);

        return switch (type) {
            case BLOB -> new Blob(payload);
            case TREE -> Tree.parse(payload);
            case COMMIT -> Commit.parse(payload);
        };
    }

    private static int indexOf(byte[] data, byte target, int from) {
        for (int i = from; i < data.length; i++) {
            if (data[i] == target) {
                return i;
            }
        }
        return -1;
    }

    /** Convenience for building a payload incrementally. */
    static ByteArrayOutputStream buffer() {
        return new ByteArrayOutputStream();
    }
}
