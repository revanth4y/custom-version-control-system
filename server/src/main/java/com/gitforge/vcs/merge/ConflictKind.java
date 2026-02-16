package com.gitforge.vcs.merge;

/** Why two sides of a merge could not be reconciled at a path. */
public enum ConflictKind {

    /** The path existed in the base and both sides changed it differently. */
    CONTENT,

    /** The path was absent from the base and both sides created it with different content. */
    ADD_ADD,

    /** One side changed the path while the other removed it. */
    MODIFY_DELETE,

    /** The content agrees but the file modes changed incompatibly. */
    MODE,

    /** One side has a file where the other has a directory. */
    TYPE
}
