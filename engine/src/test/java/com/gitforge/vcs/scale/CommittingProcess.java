package com.gitforge.vcs.scale;

import com.gitforge.vcs.object.FileMode;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.repository.FileChange;
import com.gitforge.vcs.repository.RepositoryId;
import com.gitforge.vcs.repository.VcsRepository;
import com.gitforge.vcs.repository.VcsRepositoryFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * A second process that commits to a repository the test process is also
 * committing to.
 *
 * <p>Exists because the cross-process claim cannot be tested from inside one
 * JVM. Two factories in a single process demonstrate that the in-memory locks
 * are not shared, but they cannot show whether the file lock actually excludes
 * anything, because both would be asking the same operating-system process for
 * a lock it already holds. Only a genuinely separate process settles it.
 *
 * <p>Prints one acknowledged commit id per line to stdout. The parent reads
 * them, adds its own, and checks that every one of them is still reachable when
 * both processes have finished. Anything printed here was reported to a caller
 * as a success, so anything printed here and later unreachable is a lost update.
 *
 * <p>Arguments: storage root, repository id, how many commits, and a file-name
 * prefix so the two processes write different paths and any loss is a genuine
 * reference race rather than two writers colliding on content.
 */
public final class CommittingProcess {

    private CommittingProcess() {
    }

    public static void main(String[] args) {
        if (args.length != 4) {
            System.err.println("usage: CommittingProcess <storageRoot> <repositoryId> <count> <prefix>");
            System.exit(2);
            return;
        }
        Path storageRoot = Path.of(args[0]);
        RepositoryId id = RepositoryId.of(args[1]);
        int count = Integer.parseInt(args[2]);
        String prefix = args[3];

        try {
            VcsRepository repository = new VcsRepositoryFactory(storageRoot).open(id);
            for (int i = 0; i < count; i++) {
                ObjectId commit = repository.commits().commit(
                        "main",
                        List.of(new FileChange.Put(
                                prefix + ".txt",
                                (prefix + " round " + i + "\n").getBytes(StandardCharsets.UTF_8),
                                FileMode.REGULAR_FILE)),
                        ScaleFixtures.AUTHOR,
                        prefix + " commit " + i);
                // Printed only after the call returns, so every line is a commit
                // the engine acknowledged.
                System.out.println(commit.toHex());
            }
        } catch (RuntimeException failure) {
            // A refusal is a legitimate outcome and the parent must be able to
            // tell it apart from a lost update, so it goes to stderr and takes
            // the exit code with it.
            System.err.println("FAILED: " + failure);
            System.exit(1);
        }
    }
}
