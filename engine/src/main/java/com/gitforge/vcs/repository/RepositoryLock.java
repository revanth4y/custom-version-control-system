package com.gitforge.vcs.repository;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Who may touch a repository, and when.
 *
 * <p>There are three kinds of caller and they need different things from each
 * other, so there are three ways in.
 *
 * <p><strong>{@link #reading}</strong> — walks the repository without changing
 * it, but must not have the ground moved underneath: a push computing a closure
 * is reading objects a sweep would otherwise be free to reclaim. Excluded from
 * collection, concurrent with everything else.
 *
 * <p><strong>{@link #mutating}</strong> — writes objects or moves references.
 * Excluded from collection, and <em>serialised against other mutations</em>.
 *
 * <p><strong>{@link #collecting}</strong> — reclaims unreachable objects.
 * Excludes everyone.
 *
 * <p>The middle one is the correction. Until V2.0.17 mutations took the read
 * lock, which excluded collection but not each other, and every mutation in this
 * engine is a read-then-write: a commit reads the branch tip and later moves it,
 * a branch creation checks the name is free and then claims it. Two of those
 * interleaving meant the second overwrote the first, and the caller of the first
 * had already been told it succeeded. The concurrency fixture measured four such
 * losses in fifty commits.
 *
 * <p>Serialising mutations costs nothing in reader throughput, because ordinary
 * reads — history, listings, object lookups — never come through here at all.
 * The only thing now waiting is a second writer, which previously was not
 * waiting when it needed to be.
 *
 * <p><strong>Across processes.</strong> The locks above live in one JVM, and two
 * servers over shared storage would each have their own. When a repository path
 * is supplied, mutation and collection additionally take an operating-system
 * file lock on {@code .lock} in that directory, which every process on that
 * filesystem honours, and which the operating system releases if the holder
 * dies. The in-process locks stay: they are cheaper, they give reentrancy, and
 * they guarantee this JVM never asks for the file lock twice at once, which
 * would be an error rather than a wait.
 *
 * <p>Constructed without a path, the file lock is simply absent and the
 * guarantee is one JVM only. That is what the existing tests use, and it is
 * honest about what it provides.
 */
public final class RepositoryLock {

    /**
     * The state one repository lock needs, shared by every {@code
     * RepositoryLock} in this JVM that points at the same directory.
     *
     * <p>It has to be shared rather than per-instance. Two factories over one
     * directory are two objects, and if each opened its own channel to the same
     * lock file the JVM would refuse the second outright - a file lock belongs
     * to a process, not to an object, so asking twice is an error rather than a
     * wait. Sharing also makes those two factories serialise against each other
     * in memory, which is cheaper than going out to the filesystem to discover
     * the same thing.
     */
    private static final class Guard {

        /** Held for reading and mutating, taken exclusively by collection. */
        final ReentrantReadWriteLock collection = new ReentrantReadWriteLock();

        /**
         * Serialises mutations against one another.
         *
         * <p>Reentrant because operations nest: a merge commits, a branch created
         * from a revision resolves and then creates. A non-reentrant lock would
         * deadlock a thread against itself on the second acquisition.
         */
        final ReentrantLock mutation = new ReentrantLock();

        final Path lockFile;
        private FileChannel channel;
        private FileLock held;
        private int depth;

        Guard(Path lockFile) {
            this.lockFile = lockFile;
        }

        /**
         * Takes the operating-system lock, or counts a nested entry.
         *
         * <p>Only the outermost entry touches the filesystem. Nesting is
         * legitimate - a merge commits inside its own mutation - so the depth is
         * counted rather than assumed away.
         */
        synchronized void acquire() {
            if (lockFile == null) {
                return;
            }
            if (depth++ > 0) {
                return;
            }
            try {
                Files.createDirectories(lockFile.getParent());
                channel = FileChannel.open(
                        lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                // Blocking, not a try: a second process should wait its turn
                // rather than be told the repository is busy. If that process
                // dies the operating system drops its lock, so this cannot end
                // up waiting on a holder that no longer exists.
                held = channel.lock();
            } catch (IOException | RuntimeException failure) {
                depth--;
                close();
                throw new RepositoryLockException(
                        "Could not lock the repository at " + lockFile.getParent(), failure);
            }
        }

        synchronized void release() {
            if (lockFile == null || --depth > 0) {
                return;
            }
            close();
        }

        private void close() {
            try {
                if (held != null && held.isValid()) {
                    held.release();
                }
            } catch (IOException ignored) {
                // Closing the channel below drops any lock it still holds.
            } finally {
                held = null;
                try {
                    if (channel != null) {
                        channel.close();
                    }
                } catch (IOException ignored) {
                    // Nothing useful remains to be done here, and throwing would
                    // mask the outcome the caller actually cares about.
                } finally {
                    channel = null;
                }
            }
        }
    }

    /**
     * One guard per repository directory, for the lifetime of the JVM.
     *
     * <p>Entries are never removed. A guard may be held by a thread that has not
     * reached its release yet, and dropping it from the map would hand the next
     * caller a different lock for the same files - which is the bug this map
     * exists to prevent. The cost is one small object per repository this
     * process has opened.
     */
    private static final Map<Path, Guard> GUARDS = new ConcurrentHashMap<>();

    private final Guard guard;

    /** In-process only, and shared with nothing. */
    public RepositoryLock() {
        this.guard = new Guard(null);
    }

    /**
     * In-process, and across every process that opens the same repository.
     *
     * <p>The file lives outside the repository directory, which holds objects,
     * references and HEAD and nothing else. That is not tidiness: a lock is
     * runtime state belonging to this machine, not repository content, and a
     * repository directory that gains a file the format does not define is one
     * that copies, deletes and audits wrongly.
     *
     * @param lockFile where the operating-system lock is taken; its parent
     *     directory is created on first use
     */
    public RepositoryLock(Path lockFile) {
        this.guard = lockFile == null
                ? new Guard(null)
                : GUARDS.computeIfAbsent(lockFile.toAbsolutePath().normalize(), Guard::new);
    }

    /** True when this lock also excludes other processes. */
    public boolean guardsOtherProcesses() {
        return guard.lockFile != null;
    }

    // --------------------------------------------------------------- reading

    /**
     * Runs a read-only operation that must not be collected out from under it.
     *
     * <p>Not serialised against mutations: this is for callers that look but do
     * not touch, and making them queue behind writers would turn a long closure
     * walk into a barrier for every other writer in the process.
     */
    public <T> T reading(Supplier<T> operation) {
        guard.collection.readLock().lock();
        try {
            return operation.get();
        } finally {
            guard.collection.readLock().unlock();
        }
    }

    /** As {@link #reading(Supplier)}, for an operation with no result. */
    public void reading(Runnable operation) {
        reading(() -> {
            operation.run();
            return null;
        });
    }

    // -------------------------------------------------------------- mutating

    /**
     * Runs an operation that writes objects or moves references.
     *
     * <p>One mutation at a time, per repository and - when a path was supplied -
     * per machine. The whole operation is inside the lock, not just its final
     * write, because the read that decides what to write is the half that races.
     */
    public <T> T mutating(Supplier<T> operation) {
        guard.collection.readLock().lock();
        try {
            guard.mutation.lock();
            try {
                guard.acquire();
                try {
                    return operation.get();
                } finally {
                    guard.release();
                }
            } finally {
                guard.mutation.unlock();
            }
        } finally {
            guard.collection.readLock().unlock();
        }
    }

    /** As {@link #mutating(Supplier)}, for an operation with no result. */
    public void mutating(Runnable operation) {
        mutating(() -> {
            operation.run();
            return null;
        });
    }

    // ------------------------------------------------------------ collecting

    /** Runs collection, which excludes readers, mutators and other collectors. */
    public <T> T collecting(Supplier<T> operation) {
        guard.collection.writeLock().lock();
        try {
            guard.acquire();
            try {
                return operation.get();
            } finally {
                guard.release();
            }
        } finally {
            guard.collection.writeLock().unlock();
        }
    }

    /** Raised when the repository's own lock cannot be taken. */
    public static final class RepositoryLockException extends RuntimeException {
        RepositoryLockException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
