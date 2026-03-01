package com.gitforge.vcs.tree;

import com.gitforge.vcs.object.FileMode;
import com.gitforge.vcs.object.ObjectId;

/**
 * A requested change to one path in a tree.
 *
 * <p>Distinct from {@link com.gitforge.vcs.diff.TreeChange}, which describes a
 * difference that was <em>observed</em> between two trees and therefore carries
 * both a before and an after. This describes a mutation someone is <em>asking
 * for</em>, so it carries only the intended outcome.
 *
 * <p>Works in object ids rather than content: blobs are written before a tree is
 * updated, which keeps the tree layer free of any notion of file content.
 */
public sealed interface PathUpdate permits PathUpdate.Put, PathUpdate.Remove {

    String path();

    /** Create the file at {@code path}, or replace it if it already exists. */
    record Put(String path, FileMode mode, ObjectId blob) implements PathUpdate {

        public Put {
            if (mode == null || mode.isDirectory()) {
                throw new IllegalArgumentException("A file update requires a non-directory mode");
            }
            if (blob == null) {
                throw new IllegalArgumentException("A file update requires a blob id");
            }
        }
    }

    /** Remove the file at {@code path}. */
    record Remove(String path) implements PathUpdate {
    }

    static PathUpdate put(String path, FileMode mode, ObjectId blob) {
        return new Put(path, mode, blob);
    }

    static PathUpdate remove(String path) {
        return new Remove(path);
    }
}
