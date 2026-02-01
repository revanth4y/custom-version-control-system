package com.gitforge.repo;

/** Who may read a repository. Write access is always restricted to the owner. */
public enum RepoVisibility {

    /** Readable by anyone, including unauthenticated callers. */
    PUBLIC,

    /** Readable only by the owner. Reported as absent to everyone else. */
    PRIVATE
}
