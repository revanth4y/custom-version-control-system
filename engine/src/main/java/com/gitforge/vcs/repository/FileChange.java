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

    /**
     * How many bytes this change writes; zero for a deletion.
     *
     * <p>Exists so a caller can measure a change without taking a copy of it.
     * {@link Put#content()} clones on every call, which is right for handing the
     * array out and wasteful when the only question is how big it is - and the
     * changes worth asking about are the large ones.
     */
    long size();

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

        @Override
        public long size() {
            return content.length;
        }
    }

    /** Remove the file at {@code path}. */
    record Delete(String path) implements FileChange {

        public Delete {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("A file change requires a path");
            }
        }

        @Override
        public long size() {
            return 0;
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
