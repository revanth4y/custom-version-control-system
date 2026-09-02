package com.gitforge.vcs.remote;

import com.gitforge.vcs.ref.RemoteName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Which repositories this one knows about, kept in a file beside {@code HEAD}.
 *
 * <pre>
 *   REMOTES   "origin\thttps://example.test/api/v1/repositories/octocat/demo\n"
 * </pre>
 *
 * <p>The filesystem rather than the database, deliberately. PostgreSQL holds four
 * tables — users, repositories, issues and their comments — and <em>no object ids
 * at all</em>; refs and objects live on disk. A remote is a property of a
 * repository's storage in exactly the way a ref is, and putting it in the
 * database would be the first crossing of that line for something the engine can
 * already express.
 *
 * <p>Written the way every other mutable file here is written: to a temporary
 * file in the same directory, then moved into place. An interrupted write leaves
 * the previous configuration or the new one, never half of either.
 *
 * <p>Tab-separated because a remote name cannot contain a tab — {@link RemoteName}
 * admits letters, digits, dash, underscore and dot and nothing else — so the
 * separator can never appear in the first field and the parse cannot be ambiguous.
 */
public final class RemoteStore {

    private static final String REMOTES_FILE = "REMOTES";
    private static final String TEMP_PREFIX = ".tmp-remotes-";
    private static final char SEPARATOR = '\t';

    /**
     * More remotes than any repository in this project will have, and few enough
     * that reading the file is never the expensive part of a fetch.
     */
    static final int MAX_REMOTES = 32;

    private final Path file;

    public RemoteStore(Path repositoryRoot) {
        if (repositoryRoot == null) {
            throw new IllegalArgumentException("Repository root must not be null");
        }
        this.file = repositoryRoot.toAbsolutePath().normalize().resolve(REMOTES_FILE);
    }

    /** Every remote, by name. Empty for a repository that has never had one. */
    public List<Remote> list() {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<Remote> remotes = new ArrayList<>();
        for (String line : readLines()) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            int separator = trimmed.indexOf(SEPARATOR);
            if (separator <= 0 || separator == trimmed.length() - 1) {
                throw new RemoteException("Malformed remote entry: " + trimmed);
            }
            remotes.add(new Remote(
                    trimmed.substring(0, separator), trimmed.substring(separator + 1).strip()));
        }
        remotes.sort(Comparator.comparing(Remote::name));
        return List.copyOf(remotes);
    }

    /** The remote called {@code name}, if there is one. */
    public Optional<Remote> get(String name) {
        RemoteName.validate(name);
        return list().stream().filter(remote -> remote.name().equals(name)).findFirst();
    }

    /**
     * Registers a remote, or replaces one of the same name.
     *
     * <p>Replacing rather than refusing: re-pointing a remote at a new address is
     * an ordinary thing to want, and making it a delete followed by an add would
     * leave a window in which the repository has forgotten where it was fetching
     * from.
     */
    public void save(Remote remote) {
        if (remote == null) {
            throw new RemoteException("Remote must not be null");
        }
        List<Remote> existing = new ArrayList<>(list());
        existing.removeIf(candidate -> candidate.name().equals(remote.name()));
        if (existing.size() + 1 > MAX_REMOTES) {
            throw new RemoteException("A repository may have at most " + MAX_REMOTES + " remotes");
        }
        existing.add(remote);
        existing.sort(Comparator.comparing(Remote::name));
        write(existing);
    }

    /**
     * Forgets a remote.
     *
     * <p>Only the registration goes. Its tracking refs and the objects beneath
     * them are left exactly where they are, because removing a name is not the
     * same request as reclaiming storage — the distinction branch deletion has
     * always kept.
     *
     * @return true if a remote was removed, false if there was nothing to remove
     */
    public boolean delete(String name) {
        RemoteName.validate(name);
        List<Remote> existing = new ArrayList<>(list());
        if (!existing.removeIf(candidate -> candidate.name().equals(name))) {
            return false;
        }
        write(existing);
        return true;
    }

    private List<String> readLines() {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new RemoteException("Could not read remotes from " + file, ex);
        }
    }

    private void write(List<Remote> remotes) {
        StringBuilder content = new StringBuilder();
        for (Remote remote : remotes) {
            content.append(remote.name()).append(SEPARATOR).append(remote.url()).append('\n');
        }
        writeAtomically(content.toString());
    }

    /** As {@code FileSystemRefStore}: temporary file in the same directory, then moved. */
    private void writeAtomically(String content) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), TEMP_PREFIX, null);
            try {
                Files.writeString(temp, content, StandardCharsets.UTF_8);
                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(new IOException("Could not write remotes to " + file, ex));
        }
    }
}
