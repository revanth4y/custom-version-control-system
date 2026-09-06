package com.gitforge.vcs.storage;

import com.gitforge.vcs.object.Blob;
import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.CorruptObjectException;
import com.gitforge.vcs.object.ObjectFormat;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Tree;
import com.gitforge.vcs.object.VcsObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipException;

/**
 * An object store backed by a directory tree.
 *
 * <p>Objects live at {@code objects/<first two hex chars>/<remaining 38>}. The
 * two-character shard keeps any single directory to a manageable number of
 * entries as a repository grows.
 *
 * <p>Files hold the zlib-compressed form, but identity is always the SHA-1 of
 * the <em>uncompressed</em> canonical representation. Compression is purely a
 * storage concern and never influences an object's id — which is what allows the
 * compression scheme to change later without rewriting a single hash.
 */
public final class FileSystemObjectStore implements ObjectStore {

    private static final String OBJECTS_DIRECTORY = "objects";
    private static final int SHARD_LENGTH = 2;
    private static final String TEMP_PREFIX = ".tmp-";

    /**
     * Objects already read and checked against their id, for this store only.
     *
     * <p>Never asked whether an object exists, and never consulted by
     * {@link #verify}. See {@link VerifiedObjectCache} for why both matter.
     */
    private final VerifiedObjectCache cache;

    private final Path objectsRoot;

    /**
     * @param repositoryRoot directory owned by one repository; the object store
     *     is created beneath it
     */
    public FileSystemObjectStore(Path repositoryRoot) {
        if (repositoryRoot == null) {
            throw new IllegalArgumentException("Repository root must not be null");
        }
        this.objectsRoot = repositoryRoot.resolve(OBJECTS_DIRECTORY);
        this.cache = new VerifiedObjectCache();
        try {
            Files.createDirectories(objectsRoot);
        } catch (IOException ex) {
            throw new ObjectStoreException("Could not create object store at " + objectsRoot, ex);
        }
    }

    @Override
    public ObjectId write(VcsObject object) {
        if (object == null) {
            throw new IllegalArgumentException("Object must not be null");
        }
        ObjectId id = object.id();

        // Content is addressed by its own hash, so an existing file with this id
        // already holds exactly these bytes. Rewriting it would be pure cost.
        if (contains(id)) {
            return id;
        }

        byte[] compressed = deflate(ObjectFormat.serialize(object));
        Path target = pathFor(id);

        try {
            Files.createDirectories(target.getParent());
            writeAtomically(target, compressed);
        } catch (IOException ex) {
            throw new ObjectStoreException("Could not write object " + id, ex);
        }
        // Deliberately not cached here. The id was computed from these bytes,
        // but what matters is the bytes that reached the disk, and doubting that
        // is exactly why reading verifies. Caching on the way out would mean a
        // file damaged during or after the write was never noticed by a read.
        return id;
    }

    @Override
    public Optional<VcsObject> read(ObjectId id) {
        requireId(id);
        Path path = pathFor(id);

        // Existence, and the identity of the file, are asked of the filesystem
        // every time. A sweep can remove an object at any moment, and a damaged
        // file is one the store is expected to notice; neither may be answered
        // from memory.
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, BasicFileAttributes.class);
        } catch (IOException absent) {
            return Optional.empty();
        }
        if (!attributes.isRegularFile()) {
            return Optional.empty();
        }

        VcsObject remembered = cache.get(
                id, attributes.size(), attributes.lastModifiedTime().toMillis());
        if (remembered != null) {
            // Same file, already checked against this id, and an id is the hash
            // of its object - so re-inflating would spend the whole payload to
            // reach the answer already held.
            return Optional.of(remembered);
        }
        return Optional.of(readAndVerify(id, path, attributes));
    }

    /**
     * Reads, inflates and checks an object against the id it is filed under.
     *
     * <p>The only way into the cache, and the only way {@link #verify} reads,
     * so that neither can be satisfied by something that was not checked here.
     */
    private VcsObject readAndVerify(ObjectId id, Path path, BasicFileAttributes attributes) {
        byte[] compressed;
        try {
            compressed = Files.readAllBytes(path);
        } catch (IOException ex) {
            throw new ObjectStoreException("Could not read object " + id, ex);
        }

        byte[] framed = inflate(compressed, id);
        VcsObject object = ObjectFormat.parse(framed);

        // The decisive check: the bytes on disk must hash to the id they were
        // filed under. Anything else means corruption or tampering, and must not
        // be handed back as if it were the requested object.
        ObjectId actual = ObjectId.ofContent(framed);
        if (!actual.equals(id)) {
            throw new CorruptObjectException(
                    "Object stored as " + id + " actually hashes to " + actual);
        }
        // Cached only now, past the check, under the id it was verified against
        // rather than the one it claims for itself, and stamped with the file it
        // came from so a later change to that file is a miss.
        if (attributes != null) {
            cache.put(id, object, attributes.size(), attributes.lastModifiedTime().toMillis());
        }
        return object;
    }

    @Override
    public Blob readBlob(ObjectId id) {
        VcsObject object = readRequired(id);
        if (object instanceof Blob blob) {
            return blob;
        }
        throw new CorruptObjectException("Object " + id + " is a " + object.type().header() + ", not a blob");
    }

    @Override
    public Tree readTree(ObjectId id) {
        VcsObject object = readRequired(id);
        if (object instanceof Tree tree) {
            return tree;
        }
        throw new CorruptObjectException("Object " + id + " is a " + object.type().header() + ", not a tree");
    }

    @Override
    public Commit readCommit(ObjectId id) {
        VcsObject object = readRequired(id);
        if (object instanceof Commit commit) {
            return commit;
        }
        throw new CorruptObjectException("Object " + id + " is a " + object.type().header() + ", not a commit");
    }

    @Override
    public boolean contains(ObjectId id) {
        requireId(id);
        return Files.isRegularFile(pathFor(id));
    }

    @Override
    public void verify(ObjectId id) {
        requireId(id);
        Path path = pathFor(id);
        if (!Files.isRegularFile(path)) {
            throw new CorruptObjectException("Object " + id + " is missing from the store");
        }
        // Deliberately not through the cache, and deliberately not caching
        // either. This exists to check the bytes that are on disk now; answering
        // it from something read earlier would turn an integrity scan into a
        // statement about this process's memory.
        readAndVerify(id, path, null);
    }

    @Override
    public long count() {
        long[] total = new long[1];
        eachObjectFile((shard, name) -> total[0]++);
        return total[0];
    }

    @Override
    public List<ObjectId> listIds() {
        List<ObjectId> ids = new java.util.ArrayList<>();
        // The id is reconstructed from the path: shard directory followed by the
        // remainder of the digest.
        eachObjectFile((shard, name) -> ids.add(ObjectId.fromHex(shard + name)));
        return List.copyOf(ids);
    }

    /** What to do with one object file: its shard name and its own name. */
    private interface ObjectFileVisitor {
        void accept(String shard, String name);
    }

    /**
     * Visits every object file in the store, once.
     *
     * <p>Enumerating used to walk the tree and then ask the filesystem about each
     * entry in turn, and that question was almost the whole cost: on a store of
     * thirty thousand objects the walk itself took sixty milliseconds and the
     * per-entry questions took another fourteen hundred.
     *
     * <p>The walk still asks, but it asks the directory scan, which already knows.
     * {@code walkFileTree} hands the attributes it read while listing the
     * directory, so the ordinary case costs no extra call at all.
     *
     * <p>Anything the scan does not report as a plain file is then asked about
     * properly, following links exactly as the previous implementation did. That
     * fallback never runs in a store this code wrote - it holds files and shard
     * directories and nothing else - so it costs nothing in practice while
     * keeping the answer identical for a store that has had something unexpected
     * put in it.
     */
    private void eachObjectFile(ObjectFileVisitor visitor) {
        if (!Files.isDirectory(objectsRoot)) {
            return;
        }
        try {
            Files.walkFileTree(objectsRoot, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(
                        Path file, java.nio.file.attribute.BasicFileAttributes attributes) {
                    if (!attributes.isRegularFile() && !Files.isRegularFile(file)) {
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    String name = file.getFileName().toString();
                    if (name.startsWith(TEMP_PREFIX)) {
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    Path parent = file.getParent();
                    if (parent == null || parent.equals(objectsRoot)) {
                        // A file directly under the root is not an object: the
                        // layout is objects/<shard>/<rest>. The previous walk
                        // would have tried to read an id from it and failed, so
                        // skipping it here is the same outcome without the throw.
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    visitor.accept(parent.getFileName().toString(), name);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException failure) {
                    // A file that vanished mid-walk is not in the store any more,
                    // which is the same answer the previous implementation gave
                    // when its own check found it gone.
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            throw new ObjectStoreException("Could not enumerate objects in " + objectsRoot, ex);
        }
    }

    @Override
    public long sizeOf(ObjectId id) {
        requireId(id);
        Path path = pathFor(id);
        try {
            return Files.isRegularFile(path) ? Files.size(path) : 0L;
        } catch (IOException ex) {
            throw new ObjectStoreException("Could not measure object " + id, ex);
        }
    }

    /**
     * <p>Only the object file is removed. The shard directory is left in place
     * even when it becomes empty: an empty directory costs an inode, a removed one
     * costs a race with a concurrent write that has just created the same shard
     * for a different object.
     */
    @Override
    public boolean delete(ObjectId id) {
        requireId(id);
        try {
            // Forgotten before the file goes, so no window exists in which the
            // object is gone and the cache still holds it.
            cache.evict(id);
            return Files.deleteIfExists(pathFor(id));
        } catch (IOException ex) {
            throw new ObjectStoreException("Could not delete object " + id, ex);
        }
    }

    @Override
    public List<String> temporaryFiles() {
        if (!Files.isDirectory(objectsRoot)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(objectsRoot)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(TEMP_PREFIX))
                    .map(path -> objectsRoot.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            throw new ObjectStoreException("Could not enumerate objects in " + objectsRoot, ex);
        }
    }

    @Override
    public List<ObjectId> findByPrefix(String hexPrefix) {
        String prefix = ObjectId.normalisePrefix(hexPrefix);

        // The shard is the first two characters, so it is the only directory
        // that can hold a match. A prefix shorter than the shard cannot happen:
        // the minimum prefix length is above it.
        Path shard = objectsRoot.resolve(prefix.substring(0, SHARD_LENGTH));
        if (!Files.isDirectory(shard)) {
            return List.of();
        }

        String remainder = prefix.substring(SHARD_LENGTH);
        try (Stream<Path> files = Files.list(shard)) {
            return files
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> !name.startsWith(TEMP_PREFIX))
                    .filter(name -> name.startsWith(remainder))
                    .map(name -> ObjectId.fromHex(prefix.substring(0, SHARD_LENGTH) + name))
                    // Ordered so an ambiguous answer names its candidates the
                    // same way twice running; a directory listing does not
                    // promise that on its own.
                    .sorted(Comparator.comparing(ObjectId::toHex))
                    .toList();
        } catch (IOException ex) {
            throw new ObjectStoreException("Could not search objects under " + shard, ex);
        }
    }

    /** The location of an object: {@code objects/ab/cdef...}. */
    Path pathFor(ObjectId id) {
        String hex = id.toHex();
        return objectsRoot.resolve(hex.substring(0, SHARD_LENGTH)).resolve(hex.substring(SHARD_LENGTH));
    }

    private VcsObject readRequired(ObjectId id) {
        return read(id).orElseThrow(() -> new CorruptObjectException("Object " + id + " is missing from the store"));
    }

    /**
     * Writes through a temporary file in the destination directory, then moves it
     * into place. A crash mid-write therefore cannot leave a half-written object
     * that would later fail verification.
     */
    private void writeAtomically(Path target, byte[] contents) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), TEMP_PREFIX, null);
        try {
            Files.write(temp, contents);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                // Some filesystems cannot promise atomicity; the content is
                // immutable and self-verifying, so a plain replace is acceptable.
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static byte[] deflate(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try (DeflaterOutputStream stream = new DeflaterOutputStream(out, deflater)) {
            stream.write(data);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not compress object", ex);
        } finally {
            deflater.end();
        }
        return out.toByteArray();
    }

    private static byte[] inflate(byte[] compressed, ObjectId id) {
        Inflater inflater = new Inflater();
        try (InputStream stream = new InflaterInputStream(new java.io.ByteArrayInputStream(compressed), inflater)) {
            return stream.readAllBytes();
        } catch (ZipException | java.io.EOFException ex) {
            // Truncated or damaged compressed data: the object cannot be trusted.
            throw new CorruptObjectException("Object " + id + " could not be decompressed", ex);
        } catch (IOException ex) {
            if (ex.getCause() instanceof DataFormatException) {
                throw new CorruptObjectException("Object " + id + " could not be decompressed", ex);
            }
            throw new ObjectStoreException("Could not read object " + id, ex);
        } finally {
            inflater.end();
        }
    }

    private static void requireId(ObjectId id) {
        if (id == null) {
            throw new IllegalArgumentException("Object id must not be null");
        }
    }
}
