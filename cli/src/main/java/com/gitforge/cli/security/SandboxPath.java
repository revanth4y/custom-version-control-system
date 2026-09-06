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

        Path resolved;
        try {
            resolved = base.resolve(candidate).normalize();
        } catch (java.nio.file.InvalidPathException unrepresentable) {
            // Windows forbids characters that are ordinary on POSIX. That is a
            // property of the filesystem, not an attack, and saying so is more
            // useful than letting a JDK exception surface.
            throw CliException.usage(
                    "That is not a usable file name on this system: " + candidate);
        }
        if (!startsWithin(resolved, root)) {
            throw CliException.sandbox("Path escapes the sandbox: " + candidate);
        }

        Path real = eventualTarget(resolved, candidate);
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
     * How many links to follow before giving up.
     *
     * <p>Links can point at links, and they can point at each other. A cycle
     * cannot be resolved at all, and the answer to a path whose destination
     * cannot be determined is a refusal, not an acceptance.
     */
    private static final int MAX_LINK_HOPS = 40;

    /**
     * Where a path would actually land, following links even when they dangle.
     *
     * <p>This is the check that decides containment, and the previous version of
     * it could be walked around. It asked {@code toRealPath} for the real path
     * and, when that failed, fell back to the path as written. Two things then
     * combined into an escape. A dangling symbolic link <em>exists</em> as far as
     * {@code NOFOLLOW_LINKS} is concerned, so the search for the nearest existing
     * ancestor stopped at the link itself; and {@code toRealPath} on a link whose
     * target is missing throws, so the fallback returned the link's own path -
     * which is inside the sandbox. A link inside the sandbox pointing at a name
     * outside it that did not exist yet was therefore accepted, and writing
     * through the returned path created the file outside. Creating a file is
     * exactly what a path that does not exist yet is usually for.
     *
     * <p>So a link that cannot be resolved is now followed by hand rather than
     * trusted. The link is read, its target is resolved against the directory the
     * link sits in, whatever remained of the original path is re-attached, and
     * the whole thing is asked again. That repeats while links keep appearing,
     * because a link may point at another link.
     *
     * <p>The order matters, and not only for correctness. The closest thing that
     * actually exists is found first, by walking up; only that is canonicalised,
     * and whatever was missing below it is re-attached afterwards. Asking
     * {@code toRealPath} about the whole path first would be simpler to read and
     * twice as slow in the case that dominates - creating a file, where the leaf
     * is missing by definition - because the expensive call would be made once to
     * fail and once to succeed. On a path two hundred directories deep that
     * showed up immediately as a benchmark timeout.
     *
     * <p>What is canonicalised is always a path that exists, so every link above
     * it is resolved by the filesystem, and an ordinary path, a live link and a
     * chain of live links all end up where they did before.
     *
     * <p><strong>It fails closed.</strong> Every way of not knowing - a cycle, a
     * link that cannot be read, a link that keeps resolving past the hop limit -
     * ends in a refusal. Nothing here turns an unanswered question into an
     * accepted path.
     *
     * <p>This closes a deterministic bypass; it does not close the gap between
     * this check and the caller's open, which no library can. A link swapped in
     * after the check still would not be seen, as the class comment says.
     */
    private Path eventualTarget(Path path, String candidate) {
        Path current = path;
        for (int hop = 0; hop < MAX_LINK_HOPS; hop++) {
            // The closest thing that is actually there. NOFOLLOW, so a link
            // counts as present even when what it points at is not - which is
            // the case this whole method exists for.
            Path existing = current;
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing == null) {
                // Not even a root component is there. Nothing can be reached
                // through it and nothing about it can be confirmed.
                throw CliException.sandbox(
                        "Path cannot be resolved on this filesystem: " + candidate);
            }

            if (Files.isSymbolicLink(existing)) {
                // Follow it deliberately rather than asking toRealPath, which
                // cannot answer when the target is missing. A dangling link
                // still redirects a write to wherever it points.
                Path remainder = existing.relativize(current);
                current = readLink(existing, candidate).resolve(remainder).normalize();
                continue;
            }

            try {
                // A real file or directory. Canonicalising it resolves every
                // link above it, and whatever is missing below hangs off it
                // unchanged. One canonicalisation, of a path that exists - the
                // deep-path benchmark is sensitive to doing more than that.
                Path real = existing.toRealPath();
                Path remainder = existing.relativize(current);
                return remainder.toString().isEmpty() ? real : real.resolve(remainder).normalize();
            } catch (IOException vanished) {
                // It was there a moment ago. Refuse rather than guess.
                throw CliException.sandbox(
                        "Path cannot be resolved on this filesystem: " + candidate);
            }
        }
        // Too many links, or a cycle among them.
        throw CliException.sandbox("Path follows too many links: " + candidate);
    }

    /** The target of a link, resolved against the directory the link sits in. */
    private static Path readLink(Path link, String candidate) {
        try {
            Path target = Files.readSymbolicLink(link);
            Path parent = link.getParent();
            return target.isAbsolute() || parent == null
                    ? target.toAbsolutePath().normalize()
                    : parent.resolve(target).normalize();
        } catch (IOException unreadable) {
            // A link that cannot be read is a destination that cannot be known.
            throw CliException.sandbox("Path leads through an unreadable link: " + candidate);
        }
    }

    private static Path realOf(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException notThere) {
            // Only used for the root and the base, which the constructor requires
            // to exist. Nothing to follow, so the normalized form is the answer.
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
