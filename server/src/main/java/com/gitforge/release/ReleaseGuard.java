package com.gitforge.release;

/**
 * Whether a tag is spoken for by a release.
 *
 * <p>Exists so the tag layer can ask without depending on the release layer's
 * whole surface. Deleting a tag a release names is refused, and that rule has to
 * live where releases are known about — the engine stores refs and objects and
 * has no idea releases exist, which is exactly how it should stay.
 */
public interface ReleaseGuard {

    /** Whether any release in this repository references {@code tagName}. */
    boolean isReferenced(String owner, String repositoryName, String tagName);
}
