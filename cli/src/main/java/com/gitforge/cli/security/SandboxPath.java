package com.gitforge.cli.security;

import com.gitforge.cli.CliException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * The one place a path becomes usable.
 *
 * <p>Every filesystem path the CLI touches passes through here, and nothing
 * reaches {@code Files} without it. That is the whole of the containment
 * argument: a boundary with one gate can be reasoned about, and a boundary with
 * two gates is a boundary somebody will walk around.
 *
 * <p>The check is in two parts because one is not enough.
 *
 * <p><strong>Normalization first.</strong> {@code root.resolve(path).normalize()}
 * collapses {@code ..} segments textually, and the result must still start with
 * the root. This catches {@code ../../../etc/passwd} and absolute paths pointing
 * elsewhere, and it catches them before anything is opened — the refusal costs
 * no syscall against the target.
 *
 * <p><strong>Real paths second.</strong> Textual normalization knows nothing
 * about symbolic links: {@code sandbox/link} normalizes cleanly and may still
 * lead to {@code /etc}. So where the path or any parent of it exists, it is
 * resolved through {@link Path#toRealPath} and compared against the real root.
 * A link that stays inside the sandbox is fine and stays allowed — the rule is
 * about where a path ends up, not about whether a link was involved.
 *
 * <p><strong>One namespace.</strong> The root and the base are both resolved to
 * their real form once, at construction, and every comparison happens there. Two
 * spellings of the same directory are ordinary — a symlinked home, a Windows 8.3
 * short name like {@code REVANT~1} beside {@code Revanth Y} — and comparing them
 * as text would reject paths that are plainly inside the sandbox. Resolving both
 * sides first means the check answers the question actually being asked: does
 * this path land on the same bytes as the root.
 *
 * <p><strong>The root is the boundary; the base is where relative paths start.</strong>
 * They are different questions and conflating them is a bug: resolving
 * {@code a.txt} against the root would mean the same command meant different
 * files depending on nothing the caller could see, and running from a
 * subdirectory would silently address the wrong file. Relative paths resolve
 * against the working directory, and the result is then checked against the
 * root — so the base decides <em>where</em> and the root decides <em>whether</em>.
 *
 * <p><strong>What this does not do.</strong> It cannot defend against a caller
 * who can already write to the sandbox root from outside the CLI, and it cannot
 * close the window between the check and the open — a link swapped in between
 * the two would not be seen. Closing that needs the operating system, not a
 * library, and it is named in the release notes rather than papered over here.
 */
public final class SandboxPath {

    private final Path root;
    private final Path realRoot;
    private final Path base;

    public SandboxPath(Path root) {
        this(root, Path.of(System.getProperty("user.dir", ".")));
    }

    /**
     * @param root the boundary; nothing outside it is ever opened
     * @param base where relative paths start, normally the working directory.
     *     A base outside the root is refused rather than quietly corrected: it
     *     means the caller is standing somewhere the sandbox does not cover, and
     *     silently relocating them would make every subsequent path a surprise.
     */
    public SandboxPath(Path root, Path base) {
        if (root == null) {
            throw new IllegalArgumentException("A sandbox needs a root");
        }
        this.realRoot = realOf(root.toAbsolutePath().normalize());
        this.root = this.realRoot;

        Path candidate = realOf((base == null ? this.root : base).toAbsolutePath().normalize());
        if (!startsWithin(candidate, realRoot)) {
            throw CliException.sandbox(
                    "The working directory " + candidate + " is outside the sandbox " + this.root);
        }
        this.base = candidate;
    }

    /** The sandbox root: the boundary. */
    public Path root() {
        return root;
    }

    /** Where relative paths start. */
    public Path base() {
        return base;
    }

    /**
     * Resolves a path inside the sandbox, or refuses.
     *
     * @param candidate a path, relative to the sandbox root or absolute
     * @throws CliException with {@code SANDBOX_VIOLATION} if it leads outside
     */
    public Path resolve(String candidate) {
        if (candidate == null) {
            throw CliException.usage("A path is required");
        }
        if (candidate.indexOf('\0') >= 0) {
            throw CliException.sandbox("Path contains a null byte");
        }

        Path resolved = base.resolve(candidate).normalize();
        if (!startsWithin(resolved, root)) {
            throw CliException.sandbox("Path escapes the sandbox: " + candidate);
        }

        Path real = realOf(nearestExisting(resolved));
        if (!startsWithin(real, realRoot)) {
            throw CliException.sandbox("Path escapes the sandbox through a link: " + candidate);
        }
        return resolved;
    }

    /** True when the path is inside the sandbox, without throwing. */
    public boolean contains(String candidate) {
        try {
            resolve(candidate);
            return true;
        } catch (CliException refused) {
            return false;
        }
    }

    /**
     * Walks up to the closest ancestor that exists.
     *
     * <p>{@code toRealPath} cannot resolve what is not there, and a path being
     * created legitimately does not exist yet. Its nearest existing parent is
     * what determines where it would land, so that is what is checked.
     */
    private Path nearestExisting(Path path) {
        Path candidate = path;
        while (candidate != null && !Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            candidate = candidate.getParent();
        }
        return candidate == null ? root : candidate;
    }

    private static Path realOf(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException notThere) {
            // Nothing to follow, so the normalized form is already the answer.
            return path.toAbsolutePath().normalize();
        }
    }

    /**
     * Containment by path element, not by string prefix.
     *
     * <p>{@code Path.startsWith} compares whole names, so {@code /srv/sandbox-evil}
     * is not inside {@code /srv/sandbox}. Comparing the strings would say it was.
     */
    private static boolean startsWithin(Path candidate, Path base) {
        return candidate.equals(base) || candidate.startsWith(base);
    }
}
