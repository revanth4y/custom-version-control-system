package com.gitforge.vcs.ref;

import com.gitforge.vcs.object.ObjectId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * References stored as small files, mirroring the on-disk shape of the object
 * store but for mutable data.
 *
 * <pre>
 *   refs/heads/main          "e3f1a2...\n"
 *   refs/heads/feature/login "9b2c4d...\n"
 *   HEAD                     "ref: refs/heads/main\n"  or  "&lt;40 hex&gt;\n"
 * </pre>
 *
 * <p>Every write goes through a temporary file and an atomic move, so an
 * interrupted update leaves either the previous commit id or the new one — never
 * an empty or truncated ref. That matters more here than in the object store:
 * object files are immutable and self-verifying, whereas a corrupted branch file
 * would silently lose the tip of a line of development.
 */
public final class FileSystemRefStore implements RefStore {

    private static final String REFS_DIRECTORY = "refs";
    private static final String HEADS_DIRECTORY = "heads";
    private static final String REMOTES_DIRECTORY = "remotes";
    private static final String TAGS_DIRECTORY = "tags";
    private static final String HEAD_FILE = "HEAD";
    private static final String SYMBOLIC_PREFIX = "ref: ";
    private static final String HEADS_PREFIX = "refs/heads/";
    private static final String TEMP_PREFIX = ".tmp-ref-";
    private static final String DEFAULT_BRANCH = "main";

    private final Path repositoryRoot;
    private final Path headsRoot;
    private final Path remotesRoot;
    private final Path tagsRoot;

    public FileSystemRefStore(Path repositoryRoot) {
        if (repositoryRoot == null) {
            throw new IllegalArgumentException("Repository root must not be null");
        }
        this.repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
        this.headsRoot = this.repositoryRoot.resolve(REFS_DIRECTORY).resolve(HEADS_DIRECTORY);
        this.remotesRoot = this.repositoryRoot.resolve(REFS_DIRECTORY).resolve(REMOTES_DIRECTORY);
        this.tagsRoot = this.repositoryRoot.resolve(REFS_DIRECTORY).resolve(TAGS_DIRECTORY);
        try {
            Files.createDirectories(headsRoot);
        } catch (IOException ex) {
            throw new RefException("Could not create reference store at " + headsRoot, ex);
        }
    }

    @Override
    public void createBranch(String name, ObjectId commit) {
        requireCommit(commit);
        if (branchExists(name)) {
            throw new RefException("Branch already exists: " + name);
        }
        writeBranch(name, commit);
    }

    @Override
    public Optional<ObjectId> getBranch(String name) {
        Path file = branchPath(name);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(readCommitId(file, "branch " + name));
    }

    @Override
    public boolean branchExists(String name) {
        return Files.isRegularFile(branchPath(name));
    }

    @Override
    public List<String> listBranches() {
        if (!Files.isDirectory(headsRoot)) {
            return List.of();
        }
        return refFilesUnder(headsRoot, "branches").stream()
                // Nested names are the relative path with forward slashes,
                // so feature/login reads the same on every platform.
                .map(path -> headsRoot.relativize(path).toString().replace('\\', '/'))
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    @Override
    public void updateBranch(String name, ObjectId commit) {
        requireCommit(commit);
        if (!branchExists(name)) {
            throw new RefException("Branch does not exist: " + name);
        }
        writeBranch(name, commit);
    }

    @Override
    public void deleteBranch(String name) {
        Path file = branchPath(name);
        if (!Files.isRegularFile(file)) {
            throw new RefException("Branch does not exist: " + name);
        }
        try {
            Files.delete(file);
            // Only the now-empty parents of a nested name are removed; the
            // commit and every object beneath it are untouched.
            pruneEmptyParents(file.getParent(), headsRoot);
        } catch (IOException ex) {
            throw new RefException("Could not delete branch " + name, ex);
        }
    }

    @Override
    public Head readHead() {
        Path head = repositoryRoot.resolve(HEAD_FILE);
        if (!Files.isRegularFile(head)) {
            // A repository with no HEAD yet behaves as though it is on the
            // default branch, which does not exist until the first commit.
            return Head.onBranch(DEFAULT_BRANCH);
        }

        String content = readText(head).trim();
        if (content.isEmpty()) {
            throw new RefException("HEAD is empty");
        }
        if (content.startsWith(SYMBOLIC_PREFIX)) {
            String target = content.substring(SYMBOLIC_PREFIX.length()).trim();
            if (!target.startsWith(HEADS_PREFIX)) {
                throw new RefException("HEAD points outside " + HEADS_PREFIX + ": " + target);
            }
            return Head.onBranch(target.substring(HEADS_PREFIX.length()));
        }
        try {
            return Head.detachedAt(ObjectId.fromHex(content));
        } catch (IllegalArgumentException ex) {
            throw new RefException("HEAD does not contain a valid commit id: " + content, ex);
        }
    }

    @Override
    public void setHead(Head head) {
        if (head == null) {
            throw new RefException("HEAD must not be null");
        }
        String content = switch (head) {
            case Head.OnBranch onBranch -> SYMBOLIC_PREFIX + HEADS_PREFIX + onBranch.branch() + "\n";
            case Head.Detached detached -> detached.commit().toHex() + "\n";
        };
        writeAtomically(repositoryRoot.resolve(HEAD_FILE), content);
    }

    @Override
    public Optional<ObjectId> resolveHead() {
        return switch (readHead()) {
            case Head.OnBranch onBranch -> getBranch(onBranch.branch());
            case Head.Detached detached -> Optional.of(detached.commit());
        };
    }

    @Override
    public List<RemoteRef> listRemoteRefs() {
        if (!Files.isDirectory(remotesRoot)) {
            return List.of();
        }
        return refFilesUnder(remotesRoot, "remote refs").stream()
                .map(this::toRemoteRef)
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(RemoteRef::qualifiedName))
                .toList();
    }

    @Override
    public Optional<ObjectId> getRemoteRef(String remote, String branch) {
        Path file = remoteRefPath(remote, branch);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(readCommitId(file, "Remote ref " + remote + "/" + branch));
    }

    @Override
    public void setRemoteRef(String remote, String branch, ObjectId commit) {
        requireCommit(commit);
        Path file = remoteRefPath(remote, branch);
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException ex) {
            throw new RefException("Could not create directory for remote ref " + remote + "/" + branch, ex);
        }
        writeAtomically(file, commit.toHex() + "\n");
    }

    @Override
    public boolean deleteRemoteRef(String remote, String branch) {
        Path file = remoteRefPath(remote, branch);
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            Files.delete(file);
            pruneEmptyParents(file.getParent(), remotesRoot);
            return true;
        } catch (IOException ex) {
            throw new RefException("Could not delete remote ref " + remote + "/" + branch, ex);
        }
    }

    @Override
    public int deleteRemoteRefs(String remote) {
        RemoteName.validate(remote);
        int removed = 0;
        for (RemoteRef ref : listRemoteRefs()) {
            if (ref.remote().equals(remote) && deleteRemoteRef(remote, ref.branch())) {
                removed++;
            }
        }
        return removed;
    }

    @Override
    public List<String> listTags() {
        if (!Files.isDirectory(tagsRoot)) {
            return List.of();
        }
        return refFilesUnder(tagsRoot, "tags").stream()
                // Nested names are the relative path with forward slashes, so
                // release/v1.0 reads the same on every platform.
                .map(path -> tagsRoot.relativize(path).toString().replace('\\', '/'))
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    @Override
    public Optional<ObjectId> getTag(String name) {
        Path file = tagPath(name);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(readObjectId(file, "Tag " + name));
    }

    @Override
    public boolean tagExists(String name) {
        return Files.isRegularFile(tagPath(name));
    }

    @Override
    public void createTag(String name, ObjectId target) {
        requireTarget(target);
        Path file = tagPath(name);
        if (Files.isRegularFile(file)) {
            // Immutability enforced where it cannot be worked around: there is no
            // update path to fall back to, so a caller wanting to move a tag must
            // delete it deliberately rather than overwrite it by accident.
            throw new RefException("Tag already exists: " + name);
        }
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException ex) {
            throw new RefException("Could not create directory for tag " + name, ex);
        }
        writeAtomically(file, target.toHex() + "\n");
    }

    @Override
    public boolean deleteTag(String name) {
        Path file = tagPath(name);
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            Files.delete(file);
            // Only the now-empty parents of a nested name are removed. The tag
            // object, the commit and everything beneath them are untouched.
            pruneEmptyParents(file.getParent(), tagsRoot);
            return true;
        } catch (IOException ex) {
            throw new RefException("Could not delete tag " + name, ex);
        }
    }

    /**
     * The reference files under one root.
     *
     * <p>Listing references means walking a directory that writers are changing
     * while it is walked. Every reference update writes a temporary file beside
     * its target and renames it over the top, so an entry can be enumerated and
     * then be gone before anything can be asked about it. Walking with a stream
     * and filtering afterwards cannot survive that: the filter that would have
     * excluded the temporary file never runs, because reading the attributes of
     * a name that no longer exists throws first. Measured on Linux, four readers
     * against four writers failed on the first or second listing, every time.
     *
     * <p>So the walk takes the attributes the directory scan already read, and
     * an entry that vanishes mid-walk is skipped. That skip is deliberately
     * narrow. {@link NoSuchFileException} is the filesystem saying the name is
     * not there any more, which under concurrent updates is an ordinary thing to
     * observe and not an error: the reference either was temporary and has been
     * renamed away, or was deleted, and neither belongs in the answer. Every
     * other failure — a permission problem, a failing disk — is rethrown, so a
     * store that genuinely cannot be read still says so instead of quietly
     * reporting no references.
     *
     * <p>The temporary prefix is checked before anything else is asked about the
     * entry, so the common case costs nothing extra and the file most likely to
     * disappear is the one least likely to be touched.
     */
    private List<Path> refFilesUnder(Path root, String what) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Path> found = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (file.getFileName().toString().startsWith(TEMP_PREFIX)) {
                        return FileVisitResult.CONTINUE;
                    }
                    // The scan already knows; the second question is asked only
                    // for the entries it does not report as plain files, which
                    // keeps a symbolic link to a reference visible exactly as it
                    // was before.
                    if (!attributes.isRegularFile() && !Files.isRegularFile(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    found.add(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure)
                        throws IOException {
                    if (failure instanceof NoSuchFileException) {
                        // Renamed away or deleted while the walk was running.
                        return FileVisitResult.CONTINUE;
                    }
                    throw failure;
                }
            });
        } catch (IOException ex) {
            throw new RefException("Could not list " + what + " in " + root, ex);
        }
        return found;
    }

    /**
     * The file backing a tag, after confirming the resolved path is still inside
     * {@code refs/tags}.
     *
     * <p>{@link TagName} should already have rejected anything dangerous; this
     * check is independent of it, exactly as {@link #branchPath} is independent of
     * {@link BranchName}, so a naming rule that turns out to be incomplete still
     * cannot become a write outside the refs directory.
     */
    private Path tagPath(String name) {
        TagName.validate(name);

        Path resolved = tagsRoot.resolve(name).normalize();
        if (!resolved.startsWith(tagsRoot)) {
            throw new RefException("Tag name escapes the reference directory: " + name);
        }
        return resolved;
    }

    /**
     * Reads one tracking ref back from its path.
     *
     * <p>Empty rather than throwing when the path does not describe a
     * remote-and-branch pair. A directory that somehow holds a file directly under
     * {@code refs/remotes/} names no branch, and refusing to list every other ref
     * because one entry is unreadable would make the store less useful exactly
     * when it most needs inspecting.
     */
    private Optional<RemoteRef> toRemoteRef(Path file) {
        String relative = remotesRoot.relativize(file).toString().replace('\\', '/');
        int separator = relative.indexOf('/');
        if (separator <= 0 || separator == relative.length() - 1) {
            return Optional.empty();
        }
        String remote = relative.substring(0, separator);
        String branch = relative.substring(separator + 1);
        try {
            return Optional.of(new RemoteRef(
                    remote, branch, readCommitId(file, "Remote ref " + relative)));
        } catch (RefException ex) {
            return Optional.empty();
        }
    }

    /**
     * The file backing a remote-tracking ref, after confirming the resolved path
     * is still inside {@code refs/remotes}.
     *
     * <p>Both halves are validated first — {@link RemoteName} for the remote and
     * {@link BranchName} for the branch — and this check is independent of both,
     * because a branch name here arrives from another server rather than from a
     * person using this one.
     */
    private Path remoteRefPath(String remote, String branch) {
        RemoteName.validate(remote);
        BranchName.validate(branch);

        Path resolved = remotesRoot.resolve(remote).resolve(branch).normalize();
        if (!resolved.startsWith(remotesRoot)) {
            throw new RefException("Remote ref escapes the reference directory: " + remote + "/" + branch);
        }
        return resolved;
    }

    /**
     * The file backing a branch, after confirming the resolved path is still
     * inside {@code refs/heads}.
     *
     * <p>{@link BranchName} should already have rejected anything dangerous; this
     * check is independent of it, so a naming rule that turns out to be
     * incomplete cannot become a write outside the refs directory.
     */
    private Path branchPath(String name) {
        BranchName.validate(name);

        Path resolved = headsRoot.resolve(name).normalize();
        if (!resolved.startsWith(headsRoot)) {
            throw new RefException("Branch name escapes the reference directory: " + name);
        }
        return resolved;
    }

    private void writeBranch(String name, ObjectId commit) {
        Path file = branchPath(name);
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException ex) {
            throw new RefException("Could not create directory for branch " + name, ex);
        }
        writeAtomically(file, commit.toHex() + "\n");
    }

    private ObjectId readCommitId(Path file, String description) {
        return readId(file, description, "commit");
    }

    /**
     * As {@link #readCommitId}, for a ref whose target need not be a commit.
     *
     * <p>A tag points at a commit when it is lightweight and at a tag object when
     * it is annotated, so reporting "not a valid commit id" for a damaged tag file
     * would name the wrong expectation.
     */
    private ObjectId readObjectId(Path file, String description) {
        return readId(file, description, "object");
    }

    private ObjectId readId(Path file, String description, String kind) {
        String content = readText(file).trim();
        try {
            return ObjectId.fromHex(content);
        } catch (IllegalArgumentException ex) {
            throw new RefException(
                    description + " does not contain a valid " + kind + " id: " + content, ex);
        }
    }

    private static String readText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new RefException("Could not read " + file, ex);
        }
    }

    private static void writeAtomically(Path target, String content) {
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), TEMP_PREFIX, null);
            try {
                Files.writeString(temp, content, StandardCharsets.UTF_8);
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException ex) {
            throw new RefException("Could not write " + target, ex);
        }
    }

    /** Removes directories left empty by a deletion, stopping at {@code root}. */
    private void pruneEmptyParents(Path directory, Path root) throws IOException {
        Path current = directory;
        while (current != null && !current.equals(root) && current.startsWith(root)) {
            try (Stream<Path> entries = Files.list(current)) {
                if (entries.findAny().isPresent()) {
                    return;
                }
            }
            Path parent = current.getParent();
            Files.delete(current);
            current = parent;
        }
    }

    private static void requireCommit(ObjectId commit) {
        if (commit == null) {
            throw new RefException("A branch must point at a commit");
        }
    }

    private static void requireTarget(ObjectId target) {
        if (target == null) {
            throw new RefException("A tag must point at an object");
        }
    }
}
