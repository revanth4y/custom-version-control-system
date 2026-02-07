package com.gitforge.vcs.object;

/**
 * The contents of a single file, with no name, path, or mode of its own.
 *
 * <p>Because identity is derived from content alone, the same bytes appearing at
 * ten different paths — or in a hundred commits — are one blob stored once.
 *
 * <p>Content is held as bytes, never as a string: blobs must round-trip binary
 * files and text in unknown encodings without alteration.
 */
public final class Blob implements VcsObject {

    private final byte[] content;
    private final ObjectId id;

    public Blob(byte[] content) {
        if (content == null) {
            throw new IllegalArgumentException("Blob content must not be null");
        }
        this.content = content.clone();
        this.id = ObjectFormat.computeId(ObjectType.BLOB, this.content);
    }

    @Override
    public ObjectType type() {
        return ObjectType.BLOB;
    }

    @Override
    public byte[] payload() {
        return content.clone();
    }

    @Override
    public ObjectId id() {
        return id;
    }

    public int size() {
        return content.length;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Blob blob && id.equals(blob.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Blob[" + id.abbreviate(8) + ", " + content.length + " bytes]";
    }
}
