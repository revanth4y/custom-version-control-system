package com.gitforge.vcs.object;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A directory: an ordered list of entries naming blobs and other trees.
 *
 * <p>This is the node type that makes the repository a Merkle tree. An entry
 * stores a child's id, and that id is a hash of the child's own contents, so a
 * tree's hash transitively covers everything beneath it. Changing one byte of
 * one deep file changes that blob's id, which changes its parent tree, and so on
 * up to the root.
 *
 * <p>Entries are held in canonical order regardless of the order supplied, so
 * two trees describing the same directory always serialize to the same bytes and
 * therefore share an id.
 *
 * <p>Serialized form, repeated per entry with no separators between records:
 *
 * <pre>
 *   &lt;mode&gt; &lt;name&gt;\0&lt;20 raw id bytes&gt;
 * </pre>
 *
 * <p>The id is written as 20 raw bytes, not 40 hex characters.
 */
public final class Tree implements VcsObject {

    /** Canonical order: by sort key, comparing bytes as unsigned. */
    private static final Comparator<TreeEntry> CANONICAL_ORDER =
            (left, right) -> Arrays.compareUnsigned(left.sortKey(), right.sortKey());

    private final List<TreeEntry> entries;
    private final ObjectId id;

    public Tree(Collection<TreeEntry> entries) {
        if (entries == null) {
            throw new IllegalArgumentException("Tree entries must not be null");
        }
        List<TreeEntry> sorted = new ArrayList<>(entries);
        sorted.sort(CANONICAL_ORDER);
        rejectDuplicateNames(sorted);

        this.entries = List.copyOf(sorted);
        this.id = ObjectFormat.computeId(ObjectType.TREE, serializeEntries(this.entries));
    }

    public static Tree empty() {
        return new Tree(List.of());
    }

    @Override
    public ObjectType type() {
        return ObjectType.TREE;
    }

    @Override
    public byte[] payload() {
        return serializeEntries(entries);
    }

    @Override
    public ObjectId id() {
        return id;
    }

    /** Entries in canonical order. */
    public List<TreeEntry> entries() {
        return entries;
    }

    public Optional<TreeEntry> entry(String name) {
        return entries.stream().filter(entry -> entry.name().equals(name)).findFirst();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Reads a tree back from its payload. */
    static Tree parse(byte[] payload) {
        List<TreeEntry> parsed = new ArrayList<>();
        int cursor = 0;

        while (cursor < payload.length) {
            int space = indexOf(payload, (byte) ' ', cursor);
            if (space < 0) {
                throw new CorruptObjectException("Tree entry has no space after its mode");
            }
            int nul = indexOf(payload, (byte) 0, space + 1);
            if (nul < 0) {
                throw new CorruptObjectException("Tree entry name is not terminated by a NUL byte");
            }
            if (nul + 1 + ObjectId.LENGTH > payload.length) {
                throw new CorruptObjectException("Tree entry is truncated before its object id");
            }

            String modeText = new String(payload, cursor, space - cursor, StandardCharsets.US_ASCII);
            String name = new String(payload, space + 1, nul - space - 1, StandardCharsets.UTF_8);

            FileMode mode;
            try {
                mode = FileMode.fromValue(modeText);
            } catch (IllegalArgumentException ex) {
                throw new CorruptObjectException("Tree entry has an unsupported mode: " + modeText, ex);
            }

            byte[] idBytes = Arrays.copyOfRange(payload, nul + 1, nul + 1 + ObjectId.LENGTH);

            try {
                parsed.add(new TreeEntry(mode, name, ObjectId.fromBytes(idBytes)));
            } catch (IllegalArgumentException ex) {
                throw new CorruptObjectException("Tree contains an invalid entry: " + ex.getMessage(), ex);
            }

            cursor = nul + 1 + ObjectId.LENGTH;
        }

        return new Tree(parsed);
    }

    private static byte[] serializeEntries(List<TreeEntry> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (TreeEntry entry : entries) {
            out.writeBytes(entry.mode().valueBytes());
            out.write(' ');
            out.writeBytes(entry.name().getBytes(StandardCharsets.UTF_8));
            out.write(0);
            out.writeBytes(entry.id().toBytes());
        }
        return out.toByteArray();
    }

    private static void rejectDuplicateNames(List<TreeEntry> sorted) {
        Set<String> seen = new HashSet<>();
        for (TreeEntry entry : sorted) {
            if (!seen.add(entry.name())) {
                throw new IllegalArgumentException("Tree contains a duplicate entry name: " + entry.name());
            }
        }
    }

    private static int indexOf(byte[] data, byte target, int from) {
        for (int i = from; i < data.length; i++) {
            if (data[i] == target) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Tree tree && id.equals(tree.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Tree[" + id.abbreviate(8) + ", " + entries.size() + " entries]";
    }
}
