package com.gitforge.vcs.repository;

import com.gitforge.vcs.gc.GarbageCollector;
import com.gitforge.vcs.graph.CommitGraph;
import com.gitforge.vcs.ref.BranchService;
import com.gitforge.vcs.ref.RefStore;
import com.gitforge.vcs.ref.TagService;
import com.gitforge.vcs.remote.ReceiveService;
import com.gitforge.vcs.storage.ObjectStore;

/**
 * One repository's storage, and the services scoped to it.
 *
 * <p>Deliberately a composition root rather than a facade. Every operation lives
 * on a focused collaborator — reading on {@link RepositoryReader}, writing on
 * {@link CommitService}, refs on {@link BranchService}, merging on
 * {@link MergeOrchestrator} — and this class only holds them together and hands
 * them out. A facade would accumulate every repository operation as the project
 * grows, which is exactly the shape worth avoiding.
 *
 * <p>Repositories are <em>bare</em>: objects, refs and HEAD, with no working
 * tree. A server has no meaningful single checkout — two users switching
 * branches would contend over one directory — and every read the application
 * needs is served from the immutable object store. The working-tree machinery
 * from earlier work remains available for a local clone, but is not part of a
 * server-side repository.
 *
 * <p>Instances are obtained from {@link VcsRepositoryFactory}, which is the only
 * thing that turns an id into a filesystem path.
 */
public final class VcsRepository {

    private final RepositoryId id;
    private final ObjectStore objects;
    private final RefStore refs;
    private final BranchService branches;
    private final TagService tags;
    private final RepositoryReader reader;
    private final CommitService commits;
    private final MergeOrchestrator merges;
    private final DiffService diffs;
    private final RepositoryStatistics statistics;
    private final GarbageCollector gc;
    private final ReceiveService receives;
    private final RepositoryLock lock;

    /**
     * Wires a repository with a lock of its own, for a caller holding the only
     * handle to this storage.
     *
     * <p>{@link VcsRepositoryFactory} does not use this: two callers opening the
     * same repository must share one lock, and a lock created here would be
     * private to one of them.
     */
    VcsRepository(RepositoryId id, ObjectStore objects, RefStore refs) {
        this(id, objects, refs, new RepositoryLock());
    }

    VcsRepository(RepositoryId id, ObjectStore objects, RefStore refs, RepositoryLock lock) {
        this.id = id;
        this.objects = objects;
        this.refs = refs;
        this.branches = new BranchService(refs, objects, lock);
        this.tags = new TagService(refs, objects, lock);

        CommitGraph graph = new CommitGraph(objects);
        this.reader = new RepositoryReader(objects, branches, graph);
        this.commits = new CommitService(objects, refs, branches, lock);
        this.merges = new MergeOrchestrator(objects, refs, branches, graph, lock);
        this.diffs = new DiffService(objects);
        this.statistics = new RepositoryStatistics(objects, branches, graph);

        // Null working tree: a server-side repository is bare, as described above,
        // so there is no materialized tree to protect. The collector treats that
        // as one fewer root rather than as an empty one.
        this.gc = new GarbageCollector(objects, refs, null, lock);
        this.receives = new ReceiveService(objects, refs, graph, lock);
        this.lock = lock;
    }

    public RepositoryId id() {
        return id;
    }

    public ObjectStore objects() {
        return objects;
    }

    public RefStore refs() {
        return refs;
    }

    /** Branch and HEAD operations. */
    public BranchService branches() {
        return branches;
    }

    /** Tag creation, listing, peeling and deletion. */
    public TagService tags() {
        return tags;
    }

    /** Reads: browsing, file contents, history, commit details, comparison. */
    public RepositoryReader reader() {
        return reader;
    }

    /** Writes: multi-file commits. */
    public CommitService commits() {
        return commits;
    }

    /** Repository-level merging of one branch into another. */
    public MergeOrchestrator merges() {
        return merges;
    }

    /** Line-level differences between repository states. */
    public DiffService diffs() {
        return diffs;
    }

    /** Aggregate facts derived from the object store. */
    public RepositoryStatistics statistics() {
        return statistics;
    }

    /** Reclaiming objects no reference reaches. Explicit; never runs on its own. */
    public GarbageCollector gc() {
        return gc;
    }

    /** Accepting objects and a branch move from another repository. */
    public ReceiveService receives() {
        return receives;
    }

    /**
     * The lock this repository's writers share and its collector excludes.
     *
     * <p>Exposed so a fetch or push assembled outside this class runs under the
     * same exclusion every other write does. Handing it out is safer than the
     * alternative, which is a remote operation quietly taking no lock at all.
     */
    public RepositoryLock lock() {
        return lock;
    }

    @Override
    public String toString() {
        return "VcsRepository[" + id + "]";
    }
}
