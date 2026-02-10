package com.gitforge.vcs.storage;

import com.gitforge.vcs.object.Blob;
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
        return id;
    }

    @Override
    public Optional<VcsObject> read(ObjectId id) {
        requireId(id);
        Path path = pathFor(id);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }

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
        return Optional.of(object);
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
    public boolean contains(ObjectId id) {
        requireId(id);
        return Files.isRegularFile(pathFor(id));
    }

    @Override
    public void verify(ObjectId id) {
        if (read(id).isEmpty()) {
            throw new CorruptObjectException("Object " + id + " is missing from the store");
        }
    }

    @Override
    public long count() {
        if (!Files.isDirectory(objectsRoot)) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(objectsRoot)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().startsWith(TEMP_PREFIX))
                    .count();
        } catch (IOException ex) {
            throw new ObjectStoreException("Could not enumerate objects in " + objectsRoot, ex);
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
