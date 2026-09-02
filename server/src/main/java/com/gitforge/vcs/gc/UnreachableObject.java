package com.gitforge.vcs.gc;

import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.ObjectType;

/**
 * One stored object that no root reaches.
 *
 * <p>The type is carried because it is what makes a report readable: "eleven
 * blobs and a tree" says something about what was abandoned, where twelve ids do
 * not.
 *
 * @param id the object
 * @param type what it turned out to be when read back
 * @param bytes its size on disk, compressed, which is what deleting it frees
 */
public record UnreachableObject(ObjectId id, ObjectType type, long bytes) {

    public UnreachableObject {
        if (id == null) {
            throw new IllegalArgumentException("An unreachable object must have an id");
        }
        if (type == null) {
            throw new IllegalArgumentException("An unreachable object must have a type");
        }
        if (bytes < 0) {
            throw new IllegalArgumentException("An object cannot occupy negative bytes");
        }
    }
}
