package com.gitforge.cli.local;

import com.gitforge.cli.CliException;
import com.gitforge.cli.security.SandboxPath;
import com.gitforge.vcs.repository.RepositoryId;
import com.gitforge.vcs.repository.VcsRepository;
import com.gitforge.vcs.repository.VcsRepositoryFactory;
import com.gitforge.vcs.worktree.WorkTreeState;
import com.gitforge.vcs.worktree.WorkingTree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A repository on disk, with the files that go with it.
 *
 * <p>The layout is deliberately unlike the server's. A server repository is
 * bare — objects and refs and nothing else — because no one edits files on a
 * server. A local one has a working tree, and the repository lives beneath it in
 * a metadata directory:
 *
 * <pre>
 *   myproject/            the working tree; files people edit
 *   myproject/.gitforge/  the repository: objects, refs, HEAD, index
 * </pre>
 *
 * <p>Putting the metadata inside the tree is what makes the whole directory
 * movable and what makes discovery possible from any subdirectory. It also means
 * the working tree could in principle address its own metadata, so
 * {@link #isMetadata} exists and every path that reaches the tree is checked
 * against it: a commit that stored {@code .gitforge/HEAD} as a tracked file would
 * be storing the repository inside itself.
 *
 * <p><strong>This is never a server repository.</strong> The CLI opens storage
 * it created, beneath the sandbox root, and the server's storage root is not
 * reachable from here. That is not politeness — the engine's repository lock is
 * in-process, so two processes sharing one storage directory could race a sweep
 * against a write and delete objects a commit still needed.
 */
public final class Workspace {

    /** The metadata directory inside a working tree. */
    public static final String METADATA = ".gitforge";

    /** The repository's id beneath that directory. One repository per tree. */
    private static final String REPOSITORY_ID = "repository";

    private final Path treeRoot;
    private final VcsRepository repository;
    private final WorkingTree workingTree;
    private final Index index;
    private final WorkTreeState workTreeState;

    private Workspace(Path treeRoot, VcsRepository repository, WorkingTree workingTree, Path repositoryRoot) {
        this.treeRoot = treeRoot;
        this.repository = repository;
        this.workingTree = workingTree;
        this.index = new Index(treeRoot.resolve(METADATA).resolve("index"));
        this.workTreeState = new WorkTreeState(repositoryRoot);
    }

    /**
     * Creates a repository at a directory that does not have one.
     *
     * @param defaultBranch the branch HEAD will point at before any commit
     */
    public static Workspace initialise(SandboxPath sandbox, String relativePath, String defaultBranch) {
        Path tree = sandbox.resolve(relativePath);
        Path metadata = tree.resolve(METADATA);
        if (Files.isDirectory(metadata)) {
            throw CliException.conflict("There is already a repository at " + relativePath);
        }
        try {
            Files.createDirectories(metadata);
        } catch (IOException uncreatable) {
            throw CliException.failure("Could not create " + metadata + ": " + uncreatable.getMessage());
        }
        VcsRepositoryFactory factory = new VcsRepositoryFactory(metadata);
        RepositoryId id = RepositoryId.of(REPOSITORY_ID);
        VcsRepository repository = factory.initialise(id, defaultBranch);
        return new Workspace(
                tree, repository, new WorkingTree(tree, repository.objects()), factory.pathFor(id));
    }

    /**
     * Finds the repository containing a directory.
     *
     * <p>Walks upwards, which is what makes the CLI usable from a subdirectory,
     * and stops at the sandbox root rather than at the filesystem root. Walking
     * past the sandbox could find a repository the caller was never given access
     * to, which would make the boundary decorative.
     */
    public static Workspace discover(SandboxPath sandbox, String startRelativePath) {
        Path start = sandbox.resolve(startRelativePath == null ? "." : startRelativePath);
        Path candidate = start;
        while (candidate != null && candidate.startsWith(sandbox.root())) {
            Path metadata = candidate.resolve(METADATA);
            if (Files.isDirectory(metadata)) {
                VcsRepositoryFactory factory = new VcsRepositoryFactory(metadata);
                RepositoryId id = RepositoryId.of(REPOSITORY_ID);
                if (!factory.exists(id)) {
                    throw CliException.failure(
                            "The directory " + METADATA + " at " + candidate
                                    + " does not contain a repository");
                }
                VcsRepository repository = factory.open(id);
                return new Workspace(
                        candidate, repository, new WorkingTree(candidate, repository.objects()),
                        factory.pathFor(id));
            }
            if (candidate.equals(sandbox.root())) {
                break;
            }
            candidate = candidate.getParent();
        }
        throw CliException.notFound(
                "No GitForge repository here or in any parent directory inside the sandbox. "
                        + "Run 'gitforge init' to create one.");
    }

    /** The working tree root: the directory holding {@code .gitforge}. */
    public Path treeRoot() {
        return treeRoot;
    }

    public VcsRepository repository() {
        return repository;
    }

    public WorkingTree workingTree() {
        return workingTree;
    }

    public Index index() {
        return index;
    }

    /**
     * What the working tree currently reflects.
     *
     * <p>Recorded so that checkout knows what it is replacing, and so that
     * collection treats the materialized tree as a root — objects a checkout
     * depends on must not be swept while the files are still on disk.
     */
    public WorkTreeState workTreeState() {
        return workTreeState;
    }

    /** A path relative to the working tree root, using forward slashes. */
    public String relativise(Path absolute) {
        return treeRoot.relativize(absolute).toString().replace('\\', '/');
    }

    /**
     * Whether a tree-relative path is repository metadata.
     *
     * <p>Checked on every path that would be tracked. The repository must not be
     * able to contain itself.
     */
    public static boolean isMetadata(String relativePath) {
        String normalised = relativePath.replace('\\', '/');
        return normalised.equals(METADATA) || normalised.startsWith(METADATA + "/");
    }
}
