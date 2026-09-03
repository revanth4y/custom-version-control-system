package com.gitforge.vcs.ref;

import java.util.Set;

/**
 * The rules that govern what a tag may be called.
 *
 * <p>Deliberately not {@link BranchName}, and not {@link RemoteName} either. A tag
 * name becomes a path under {@code refs/tags}, so it shares the path-safety
 * requirements of a branch and permits the same slash hierarchy — {@code
 * release/v1.0} is a legal tag. What it does not share is the contract: a branch
 * is a moving pointer people rename and delete freely, while a tag is a permanent
 * reference a release is published against, and the two deserve their own rules
 * and their own messages rather than one class serving both by coincidence.
 *
 * <p>Two rules exist here that have no branch equivalent, and both come from the
 * fact that tags participate in revision resolution:
 *
 * <ul>
 *   <li>a name that is exactly a full object id is refused, because such a tag
 *       would shadow the object it is named after;
 *   <li>a length ceiling, because a tag name is chosen once and kept, so there is
 *       no reason to admit one no filesystem will store.
 * </ul>
 *
 * <p>As with branches this is the first of two defences. {@link FileSystemRefStore}
 * independently confirms the resolved path stays inside the tags directory, so a
 * rule missed here still cannot escape.
 */
public final class TagName {

    /** Characters that carry meaning in revision syntax, or that break paths. */
    private static final Set<Character> FORBIDDEN_CHARACTERS =
            Set.of('~', '^', ':', '?', '*', '[', ']', '\\', '"', '\'', '<', '>', '|', ' ');

    /** Reserved because it names the special HEAD reference. */
    private static final String RESERVED_HEAD = "HEAD";

    private static final String LOCK_SUFFIX = ".lock";

    /**
     * The length of a full object id in hexadecimal.
     *
     * <p>A tag of exactly this shape is refused rather than resolved by
     * precedence: a deterministic rule that hides an object behind a tag is still
     * a surprise, and refusing the name costs nothing.
     */
    private static final int OBJECT_ID_LENGTH = 40;

    /**
     * Comfortably beyond any name a person would choose, and short enough that
     * the whole name fits a path on every filesystem the project runs on even
     * when it nests.
     */
    private static final int MAX_LENGTH = 255;

    private TagName() {
    }

    /**
     * Returns {@code name} unchanged if it is a legal tag name.
     *
     * @throws RefException if the name is unsafe or malformed
     */
    public static String validate(String name) {
        if (name == null || name.isBlank()) {
            throw new RefException("Tag name must not be empty");
        }
        if (name.length() > MAX_LENGTH) {
            throw new RefException("Tag name must be at most " + MAX_LENGTH + " characters: " + name);
        }
        if (name.equals(RESERVED_HEAD)) {
            throw new RefException("Tag name '" + RESERVED_HEAD + "' is reserved");
        }
        if (name.startsWith("/") || name.endsWith("/")) {
            throw new RefException("Tag name must not start or end with '/': " + name);
        }
        if (name.contains("//")) {
            throw new RefException("Tag name must not contain an empty segment: " + name);
        }
        // Catches absolute Windows paths such as C:\work before the character
        // scan reports something less specific.
        if (name.length() > 1 && name.charAt(1) == ':') {
            throw new RefException("Tag name must not be an absolute path: " + name);
        }

        for (char character : name.toCharArray()) {
            if (character < 0x20 || character == 0x7F) {
                throw new RefException("Tag name must not contain control characters: " + name);
            }
            if (FORBIDDEN_CHARACTERS.contains(character)) {
                throw new RefException("Tag name must not contain '" + character + "': " + name);
            }
        }
        if (name.contains("@{")) {
            throw new RefException("Tag name must not contain '@{': " + name);
        }
        if (looksLikeAnObjectId(name)) {
            throw new RefException("Tag name must not be an object id: " + name);
        }

        for (String segment : name.split("/", -1)) {
            validateSegment(segment, name);
        }
        return name;
    }

    private static void validateSegment(String segment, String fullName) {
        if (segment.isEmpty()) {
            throw new RefException("Tag name must not contain an empty segment: " + fullName);
        }
        // The escape vector: these would resolve outside refs/tags.
        if (segment.equals(".") || segment.equals("..")) {
            throw new RefException("Tag name must not contain '.' or '..' segments: " + fullName);
        }
        if (segment.startsWith(".")) {
            throw new RefException("Tag name segments must not start with '.': " + fullName);
        }
        if (segment.startsWith("-")) {
            throw new RefException("Tag name segments must not start with '-': " + fullName);
        }
        if (segment.endsWith(LOCK_SUFFIX)) {
            throw new RefException("Tag name segments must not end with '" + LOCK_SUFFIX + "': " + fullName);
        }
    }

    /** Exactly forty hexadecimal digits, the shape a full object id takes. */
    private static boolean looksLikeAnObjectId(String name) {
        if (name.length() != OBJECT_ID_LENGTH) {
            return false;
        }
        for (char character : name.toCharArray()) {
            boolean hex = (character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f')
                    || (character >= 'A' && character <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
