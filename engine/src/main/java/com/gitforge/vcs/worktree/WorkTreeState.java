package com.gitforge.vcs.worktree;

import com.gitforge.vcs.object.ObjectId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Which tree the working directory currently reflects.
 *
 * <p>This is deliberately <em>not</em> the same question as "what does HEAD
 * name". HEAD can point at a branch whose files were never written to disk — a
 * newly initialised repository is exactly that case. Deriving the working tree's
 * baseline from HEAD would then read an empty directory as "every tracked file
 * has been deleted" and refuse the very first checkout.
 *
 * <p>So the baseline is recorded when files are actually materialized. Before any
 * checkout there is no record, everything on disk is untracked, and nothing can
 * be reported as locally deleted.
 *
 * <p>Kept in the repository metadata directory rather than in the working tree
 * itself, so it never appears as an untracked file.
 */
public final class WorkTreeState {

    private static final String STATE_FILE = "WORKTREE";
    private static final String TEMP_PREFIX = ".tmp-worktree-";

    private final Path stateFile;

    public WorkTreeState(Path repositoryRoot) {
        if (repositoryRoot == null) {
            throw new IllegalArgumentException("Repository root must not be null");
        }
        this.stateFile = repositoryRoot.toAbsolutePath().normalize().resolve(STATE_FILE);
    }

    /** The tree last materialized, or empty if the working tree was never populated. */
    public Optional<ObjectId> materializedTree() {
        if (!Files.isRegularFile(stateFile)) {
            return Optional.empty();
        }
        String content;
        try {
            content = Files.readString(stateFile, StandardCharsets.UTF_8).trim();
        } catch (IOException ex) {
            throw new WorkingTreeException("Could not read working tree state at " + stateFile, ex);
        }
        try {
            return Optional.of(ObjectId.fromHex(content));
        } catch (IllegalArgumentException ex) {
            throw new WorkingTreeException("Working tree state is not a valid tree id: " + content, ex);
        }
    }

    /** Records the tree the working directory now reflects. */
    public void record(ObjectId treeId) {
        if (treeId == null) {
            throw new IllegalArgumentException("Tree id must not be null");
        }
        try {
            Files.createDirectories(stateFile.getParent());
            Path temp = Files.createTempFile(stateFile.getParent(), TEMP_PREFIX, null);
            try {
                Files.writeString(temp, treeId.toHex() + "\n", StandardCharsets.UTF_8);
                try {
                    Files.move(temp, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException ex) {
            throw new WorkingTreeException("Could not record working tree state at " + stateFile, ex);
        }
    }
}
