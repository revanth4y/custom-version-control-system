package com.gitforge.vcs;

import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.FileMode;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Signature;
import com.gitforge.vcs.ref.BranchService;
import com.gitforge.vcs.ref.FileSystemRefStore;
import com.gitforge.vcs.ref.RefStore;
import com.gitforge.vcs.storage.FileSystemObjectStore;
import com.gitforge.vcs.storage.ObjectStore;
import com.gitforge.vcs.tree.TreeBuilder;
import com.gitforge.vcs.worktree.CheckoutService;
import com.gitforge.vcs.worktree.WorkTreeState;
import com.gitforge.vcs.worktree.WorkingTree;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A wired-up repository for tests: object store, references, and a working tree
 * rooted separately from the metadata.
 *
 * <p>Commits are given increasing timestamps so that two commits with the same
 * content and parents still receive distinct ids.
 */
public final class RepositoryFixture {

    private final ObjectStore objectStore;
    private final RefStore refStore;
    private final BranchService branchService;
    private final WorkingTree workingTree;
    private final WorkTreeState workTreeState;
    private final CheckoutService checkoutService;

    private int sequence;

    public RepositoryFixture(Path repositoryRoot, Path workingTreeRoot) {
        this.objectStore = new FileSystemObjectStore(repositoryRoot);
        this.refStore = new FileSystemRefStore(repositoryRoot);
        this.branchService = new BranchService(refStore, objectStore);
        this.workingTree = new WorkingTree(workingTreeRoot, objectStore);
        this.workTreeState = new WorkTreeState(repositoryRoot);
        this.checkoutService = new CheckoutService(
                refStore, branchService, objectStore, workingTree, workTreeState);
    }

    public ObjectStore objectStore() {
        return objectStore;
    }

    public RefStore refStore() {
        return refStore;
    }

    public BranchService branches() {
        return branchService;
    }

    public WorkingTree workingTree() {
        return workingTree;
    }

    public CheckoutService checkout() {
        return checkoutService;
    }

    public WorkTreeState workTreeState() {
        return workTreeState;
    }

    public static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** Describes a file to place in a commit. */
    public record FileSpec(byte[] content, FileMode mode) {

        public static FileSpec of(String content) {
            return new FileSpec(bytes(content), FileMode.REGULAR_FILE);
        }

        public static FileSpec binary(byte[] content) {
            return new FileSpec(content, FileMode.REGULAR_FILE);
        }

        public static FileSpec executable(String content) {
            return new FileSpec(bytes(content), FileMode.EXECUTABLE_FILE);
        }
    }

    /** Builds a tree from path to content, writing every object. */
    public ObjectId buildTree(Map<String, FileSpec> files) {
        TreeBuilder builder = new TreeBuilder(objectStore);
        files.forEach((path, spec) -> builder.addFile(path, spec.content(), spec.mode()));
        return builder.build();
    }

    /** Creates and stores a commit over the given files. */
    public ObjectId commit(String message, ObjectId parent, Map<String, FileSpec> files) {
        ObjectId tree = buildTree(files);
        List<ObjectId> parents = parent == null ? List.of() : List.of(parent);

        Signature author = new Signature(
                "Ada Lovelace",
                "ada@example.com",
                Instant.ofEpochSecond(1_700_000_000L + sequence++),
                ZoneOffset.UTC);

        Commit created = Commit.of(tree, parents, author, message);
        return objectStore.write(created);
    }

    /** Convenience for a single-file commit. */
    public ObjectId commit(String message, ObjectId parent, String path, String content) {
        Map<String, FileSpec> files = new LinkedHashMap<>();
        files.put(path, FileSpec.of(content));
        return commit(message, parent, files);
    }

    public static Map<String, FileSpec> files(Object... pathsAndContents) {
        Map<String, FileSpec> files = new LinkedHashMap<>();
        for (int i = 0; i < pathsAndContents.length; i += 2) {
            String path = (String) pathsAndContents[i];
            Object value = pathsAndContents[i + 1];
            files.put(path, value instanceof FileSpec spec ? spec : FileSpec.of((String) value));
        }
        return files;
    }
}
