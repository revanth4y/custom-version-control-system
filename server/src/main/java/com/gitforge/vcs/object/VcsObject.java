package com.gitforge.vcs.object;

/**
 * An object addressable by the SHA-1 of its canonical representation.
 *
 * <p>Sealed so the compiler can check that every consumer handles each kind.
 * When commits join this hierarchy, sites that need updating become compile
 * errors rather than runtime surprises.
 */
public sealed interface VcsObject permits Blob, Tree, Commit {

    ObjectType type();

    /**
     * The object body, excluding the {@code <type> <length>\0} header.
     *
     * <p>Implementations return a copy: the payload determines the object's
     * identity, so it must not be mutable through this accessor.
     */
    byte[] payload();

    /** The SHA-1 of this object's full canonical representation, header included. */
    ObjectId id();
}
