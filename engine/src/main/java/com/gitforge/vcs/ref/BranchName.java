package com.gitforge.vcs.ref;

import java.util.Set;

/**
 * The single place branch naming rules are defined.
 *
 * <p>A branch name becomes a path under {@code refs/heads}, so an unvalidated
 * name is a filesystem write primitive: {@code ../../objects/ab/cdef} would let
 * a caller overwrite an immutable object through the mutable ref API. Validation
 * here is the first of two defences; {@link FileSystemRefStore} independently
 * confirms that the resolved path stays inside the refs directory, so a rule
 * missed here still cannot escape.
 *
 * <p>Slashes are permitted as hierarchy separators, so {@code feature/login} is a
 * valid name that nests one directory deep.
 */
public final class BranchName {

    /** Characters that carry meaning in revision syntax, or that break paths. */
    private static final Set<Character> FORBIDDEN_CHARACTERS =
            Set.of('~', '^', ':', '?', '*', '[', ']', '\\', '"', '\'', '<', '>', '|', ' ');

    /** Reserved because it names the special HEAD reference. */
    private static final String RESERVED_HEAD = "HEAD";

    private static final String LOCK_SUFFIX = ".lock";

    private BranchName() {
    }

    /**
     * Returns {@code name} unchanged if it is a legal branch name.
     *
     * @throws RefException if the name is unsafe or malformed
     */
    public static String validate(String name) {
        if (name == null || name.isBlank()) {
            throw new RefException("Branch name must not be empty");
        }
        if (name.equals(RESERVED_HEAD)) {
            throw new RefException("Branch name '" + RESERVED_HEAD + "' is reserved");
        }
        if (name.startsWith("/") || name.endsWith("/")) {
            throw new RefException("Branch name must not start or end with '/': " + name);
        }
        if (name.contains("//")) {
            throw new RefException("Branch name must not contain an empty segment: " + name);
        }
        // Catches absolute Windows paths such as C:\work before the character
        // scan reports something less specific.
        if (name.length() > 1 && name.charAt(1) == ':') {
            throw new RefException("Branch name must not be an absolute path: " + name);
        }

        for (char character : name.toCharArray()) {
            if (character < 0x20 || character == 0x7F) {
                throw new RefException("Branch name must not contain control characters: " + name);
            }
            if (FORBIDDEN_CHARACTERS.contains(character)) {
                throw new RefException("Branch name must not contain '" + character + "': " + name);
            }
        }
        if (name.contains("@{")) {
            throw new RefException("Branch name must not contain '@{': " + name);
        }

        for (String segment : name.split("/", -1)) {
            validateSegment(segment, name);
        }
        return name;
    }

    private static void validateSegment(String segment, String fullName) {
        if (segment.isEmpty()) {
            throw new RefException("Branch name must not contain an empty segment: " + fullName);
        }
        // The escape vector: these would resolve outside refs/heads.
        if (segment.equals(".") || segment.equals("..")) {
            throw new RefException("Branch name must not contain '.' or '..' segments: " + fullName);
        }
        if (segment.startsWith(".")) {
            throw new RefException("Branch name segments must not start with '.': " + fullName);
        }
        if (segment.startsWith("-")) {
            throw new RefException("Branch name segments must not start with '-': " + fullName);
        }
        if (segment.endsWith(LOCK_SUFFIX)) {
            throw new RefException("Branch name segments must not end with '" + LOCK_SUFFIX + "': " + fullName);
        }
    }
}
