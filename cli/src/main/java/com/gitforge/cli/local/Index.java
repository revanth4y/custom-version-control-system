package com.gitforge.cli.local;

import com.gitforge.cli.CliException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the next commit will contain.
 *
 * <p>The engine has no staging area, and deliberately so: its
 * {@code CommitService} takes the changes it should record and writes them, which
 * is the right shape for a server that receives a complete set of changes in one
 * request. Staging is a thing people do while editing, so it belongs to the tool
 * people edit with rather than to the engine.
 *
 * <p>A sorted set of tree-relative paths, one per line. Sorted rather than
 * insertion-ordered because the order a person happened to add files in is not
 * information, and a stable file makes {@code status} output deterministic.
 *
 * <p>Paths only, not content. Content is read from the working tree at commit
 * time, so what is committed is what is on disk when the commit is made. The
 * alternative — copying content in at add time — means a file edited after being
 * added is committed in its earlier state, which is a real Git behaviour and a
 * reliable source of confusion. This is the simpler promise, and it is the one
 * the help text makes.
 */
public final class Index {

    private final Path file;

    public Index(Path file) {
        this.file = file;
    }

    /** The staged paths, sorted. */
    public Set<String> staged() {
        Set<String> paths = new TreeSet<>();
        if (!Files.isRegularFile(file)) {
            return paths;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    paths.add(trimmed);
                }
            }
        } catch (IOException unreadable) {
            throw CliException.failure("Could not read the index: " + unreadable.getMessage());
        }
        return paths;
    }

    /** Adds paths. Returns those that were not already staged. */
    public List<String> add(List<String> paths) {
        Set<String> current = staged();
        Set<String> added = new LinkedHashSet<>();
        for (String path : paths) {
            if (Workspace.isMetadata(path)) {
                throw CliException.usage("Refusing to track repository metadata: " + path);
            }
            if (current.add(path)) {
                added.add(path);
            }
        }
        write(current);
        return List.copyOf(added);
    }

    /** Removes paths from the index without touching the working tree. */
    public List<String> remove(List<String> paths) {
        Set<String> current = staged();
        Set<String> removed = new LinkedHashSet<>();
        for (String path : paths) {
            if (current.remove(path)) {
                removed.add(path);
            }
        }
        write(current);
        return List.copyOf(removed);
    }

    /** Empties the index, after a commit has taken what it held. */
    public void clear() {
        write(Set.of());
    }

    private void write(Set<String> paths) {
        StringBuilder body = new StringBuilder();
        new TreeSet<>(paths).forEach(path -> body.append(path).append('\n'));
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, body.toString(), StandardCharsets.UTF_8);
        } catch (IOException unwritable) {
            throw CliException.failure("Could not write the index: " + unwritable.getMessage());
        }
    }
}
