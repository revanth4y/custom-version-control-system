package com.gitforge.vcs.repository;

import com.gitforge.vcs.object.FileMode;

/**
 * A requested change to one file, carrying content rather than object ids.
 *
 * <p>This is the form a caller supplies: raw bytes, before any blob exists.
 * {@link CommitService} writes the blobs and converts these into
 * {@link com.gitforge.vcs.tree.PathUpdate}s, which keeps the tree layer working
 * purely in object ids and unaware of file content.
 */
public sealed interface FileChange permits FileChange.Put, FileChange.Delete {

    String path();

    /** Create or replace the file at {@code path}. */
    record Put(String path, byte[] content, FileMode mode) implements FileChange {

        public Put {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("A file change requires a path");
            }
            if (content == null) {
                throw new IllegalArgumentException("A file change requires content");
            }
            if (mode == null || mode.isDirectory()) {
                throw new IllegalArgumentException("A file change requires a non-directory mode");
            }
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    /** Remove the file at {@code path}. */
    record Delete(String path) implements FileChange {

        public Delete {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("A file change requires a path");
            }
        }
    }

    static FileChange put(String path, byte[] content) {
        return new Put(path, content, FileMode.REGULAR_FILE);
    }

    static FileChange put(String path, byte[] content, FileMode mode) {
        return new Put(path, content, mode);
    }

    static FileChange delete(String path) {
        return new Delete(path);
    }
}
