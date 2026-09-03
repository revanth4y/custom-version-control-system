package com.gitforge.vcs.object;

import java.nio.charset.StandardCharsets;

/**
 * One line of a directory listing: a mode, a name, and the id of the object the
 * name refers to.
 *
 * @param mode how the entry should be interpreted
 * @param name a single path component, never a path
 * @param id the blob this file names, or the tree this directory names
 */
public record TreeEntry(FileMode mode, String name, ObjectId id) {

    public TreeEntry {
        if (mode == null) {
            throw new IllegalArgumentException("Tree entry mode must not be null");
        }
        if (id == null) {
            throw new IllegalArgumentException("Tree entry id must not be null");
        }
        validateName(name);
    }

    public boolean isDirectory() {
        return mode.isDirectory();
    }

    /**
     * The bytes this entry is sorted by.
     *
     * <p>A directory sorts as though its name ended in {@code /}. That is not a
     * cosmetic detail: it is why {@code src.txt} precedes the directory
     * {@code src} — {@code .} (0x2E) is below {@code /} (0x2F) — while
     * {@code src} precedes {@code src0}, since {@code /} is below {@code 0}
     * (0x30). Ordering feeds directly into the tree's hash, so getting this
     * wrong yields a different id for identical content.
     */
    byte[] sortKey() {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        if (!isDirectory()) {
            return nameBytes;
        }
        byte[] key = new byte[nameBytes.length + 1];
        System.arraycopy(nameBytes, 0, key, 0, nameBytes.length);
        key[nameBytes.length] = '/';
        return key;
    }

    private static void validateName(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Tree entry name must not be empty");
        }
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Tree entry name must be a single path component: " + name);
        }
        if (name.indexOf('\0') >= 0) {
            // A NUL would terminate the name early when the entry is serialized.
            throw new IllegalArgumentException("Tree entry name must not contain a NUL byte");
        }
        if (name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("Tree entry name must not be a relative path segment: " + name);
        }
    }
}
