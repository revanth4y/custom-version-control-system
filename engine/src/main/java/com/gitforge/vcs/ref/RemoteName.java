package com.gitforge.vcs.ref;

/**
 * The rules that govern what a remote may be called.
 *
 * <p>Separate from {@link BranchName} on purpose. A remote name is one path
 * segment under {@code refs/remotes/}, never a hierarchy, so the slashes that
 * make {@code feature/login} a legal branch would here make {@code origin/main}
 * indistinguishable from a remote called {@code origin} tracking {@code main}.
 * Sharing the branch rules would buy nothing and lose that distinction.
 *
 * <p>The name is also read back from configuration written by an earlier run, so
 * it is validated on every use rather than only when a remote is registered — the
 * same reason {@code FileSystemRefStore} validates a branch name on every lookup.
 */
public final class RemoteName {

    /**
     * Long enough for any name a person would choose, short enough that a
     * directory name stays within every filesystem's limit once a nested branch
     * name is appended beneath it.
     */
    private static final int MAX_LENGTH = 64;

    private RemoteName() {
    }

    /**
     * Returns {@code name} unchanged if it is a legal remote name.
     *
     * @throws RefException if the name is unsafe or malformed
     */
    public static String validate(String name) {
        if (name == null || name.isBlank()) {
            throw new RefException("Remote name must not be empty");
        }
        if (name.length() > MAX_LENGTH) {
            throw new RefException(
                    "Remote name must be at most " + MAX_LENGTH + " characters: " + name);
        }
        if (name.contains("/") || name.contains("\\")) {
            throw new RefException("Remote name must be a single segment: " + name);
        }
        // Refused before the character rules so the message names the real
        // problem: these are the two segments that walk up a directory tree.
        if (name.equals(".") || name.equals("..")) {
            throw new RefException("Remote name must not be a relative path segment: " + name);
        }
        if (name.startsWith(".") || name.startsWith("-")) {
            throw new RefException("Remote name must not start with '.' or '-': " + name);
        }
        for (char character : name.toCharArray()) {
            if (!isPermitted(character)) {
                throw new RefException("Remote name must not contain '" + character + "': " + name);
            }
        }
        return name;
    }

    /**
     * Letters, digits, dash, underscore and dot.
     *
     * <p>An allow-list rather than a deny-list. A remote name becomes a directory
     * name, and every name this admits is safe on every filesystem the project
     * runs on — which is a stronger claim than enumerating the characters that
     * happen to be dangerous today.
     */
    private static boolean isPermitted(char character) {
        return (character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z')
                || (character >= '0' && character <= '9')
                || character == '-'
                || character == '_'
                || character == '.';
    }
}
