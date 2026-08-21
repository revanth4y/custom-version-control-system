package com.gitforge.vcs.repository;

/**
 * Identifies one repository's storage, and is the boundary that keeps
 * repositories apart.
 *
 * <p>The value becomes a directory name under the storage root, so an
 * unvalidated id is a filesystem primitive: {@code ../other-repo} would reach
 * another repository's objects and refs through an API that looks like it only
 * names your own.
 *
 * <p>Validation here is the first of two defences. {@link VcsRepositoryFactory}
 * independently confirms that the resolved path still sits inside the storage
 * root, so a rule missing from this class cannot on its own let one repository
 * reach another.
 *
 * <p>The permitted charset is deliberately narrow rather than merely
 * "dangerous characters removed": application ids are UUIDs, so nothing useful
 * is lost by refusing everything that is not plainly a name.
 */
public final class RepositoryId {

    private static final int MAX_LENGTH = 64;

    private final String value;

    private RepositoryId(String value) {
        this.value = value;
    }

    /**
     * @throws IllegalArgumentException if the id could name anything other than a
     *     single directory directly beneath the storage root
     */
    public static RepositoryId of(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Repository id must not be empty");
        }
        if (raw.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Repository id must be at most " + MAX_LENGTH + " characters, got " + raw.length());
        }
        // The escape vectors: a path segment that is not a plain name.
        if (raw.equals(".") || raw.equals("..")) {
            throw new IllegalArgumentException("Repository id must not be a relative path segment: " + raw);
        }
        for (char character : raw.toCharArray()) {
            if (!isPermitted(character)) {
                throw new IllegalArgumentException(
                        "Repository id must contain only letters, digits, '.', '_' or '-', got: " + raw);
            }
        }
        return new RepositoryId(raw);
    }

    private static boolean isPermitted(char character) {
        return (character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z')
                || (character >= '0' && character <= '9')
                || character == '.' || character == '_' || character == '-';
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RepositoryId id && value.equals(id.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
