package com.gitforge.vcs.remote;

import com.gitforge.vcs.object.Blob;
import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Tag;
import com.gitforge.vcs.object.Tree;
import com.gitforge.vcs.object.TreeEntry;
import com.gitforge.vcs.object.VcsObject;
import com.gitforge.vcs.ref.BranchName;
import com.gitforge.vcs.ref.RefException;
import com.gitforge.vcs.ref.RefStore;
import com.gitforge.vcs.repository.RepositoryLock;
import com.gitforge.vcs.storage.ObjectStore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Bringing another repository's history here.
 *
 * <p>The walk is driven from this side, one layer at a time. This repository asks
 * the peer for the branch tips, then for whatever those tips reference that it
 * does not already hold, then for whatever <em>those</em> objects reference, and
 * so on until nothing is missing. The peer is never asked to work out what is
 * needed — it only answers "here are the objects with these ids" — so a peer
 * cannot steer the walk, and an object already held is never requested.
 *
 * <p>Every object is verified as it arrives. Nothing is trusted because of where
 * it came from.
 *
 * <p><strong>Tracking refs move last.</strong> The whole fetch runs inside one
 * shared lock, and no reference is written until every object beneath every tip
 * is durable. A fetch that fails part-way therefore leaves objects on disk and no
 * reference pointing at them — unreachable, harmless, and collectable by the
 * explicit sweep, exactly as an interrupted commit is.
 *
 * <p><strong>Only branches this repository can name are tracked.</strong> A ref
 * name from a peer is validated before it becomes a path, and one that fails is
 * skipped rather than failing the fetch: one unusable name on the other side is
 * not a reason to refuse everything else it offered.
 */
public final class FetchService {

    private final ObjectStore objects;
    private final RefStore refs;
    private final RemoteTransport transport;
    private final RepositoryLock lock;

    public FetchService(
            ObjectStore objects, RefStore refs, RemoteTransport transport, RepositoryLock lock) {

        if (objects == null || refs == null || transport == null || lock == null) {
            throw new IllegalArgumentException(
                    "Fetching needs a store, references, a transport and a lock");
        }
        this.objects = objects;
        this.refs = refs;
        this.transport = transport;
        this.lock = lock;
    }

    /** Fetches every branch the remote advertises. */
    public Result fetch(Remote remote) {
        if (remote == null) {
            throw new RemoteException("A fetch needs a remote");
        }
        List<RemoteTransport.RemoteBranch> advertised = transport.advertise(remote);
        return lock.mutating(() -> apply(remote, advertised));
    }

    private Result apply(Remote remote, List<RemoteTransport.RemoteBranch> advertised) {
        List<String> skipped = new ArrayList<>();
        List<Wanted> wanted = new ArrayList<>();

        for (RemoteTransport.RemoteBranch branch : advertised) {
            try {
                BranchName.validate(branch.branch());
            } catch (RefException ex) {
                skipped.add(branch.branch());
                continue;
            }
            try {
                wanted.add(new Wanted(branch.branch(), ObjectId.fromHex(branch.commit())));
            } catch (IllegalArgumentException ex) {
                skipped.add(branch.branch());
            }
        }

        Set<ObjectId> tips = new LinkedHashSet<>();
        wanted.forEach(entry -> tips.add(entry.commit()));

        int received = transfer(remote, tips);

        // Everything beneath every tip is durable. Only now do the tracking refs
        // move, and they move together.
        List<String> updated = new ArrayList<>();
        for (Wanted entry : wanted) {
            refs.setRemoteRef(remote.name(), entry.branch(), entry.commit());
            updated.add(remote.name() + "/" + entry.branch());
        }
        return new Result(updated, received, skipped);
    }

    /**
     * Asks for what is missing, layer by layer, until nothing is.
     *
     * <p>Each round strictly reduces what remains unresolved: either an object
     * arrives, or the round fails. The round ceiling exists for a peer that
     * answers without actually sending what was asked for.
     */
    private int transfer(Remote remote, Set<ObjectId> tips) {
        Set<ObjectId> resolved = new LinkedHashSet<>();
        Set<ObjectId> frontier = new LinkedHashSet<>(tips);
        int received = 0;
        int rounds = 0;

        while (!frontier.isEmpty()) {
            if (++rounds > TransferLimits.MAX_TRANSFER_ROUNDS) {
                throw new RemoteException(
                        "The remote did not finish sending after "
                                + TransferLimits.MAX_TRANSFER_ROUNDS + " rounds");
            }

            List<ObjectId> absent = frontier.stream().filter(id -> !objects.contains(id)).toList();
            if (!absent.isEmpty()) {
                received += request(remote, absent);
            }

            Set<ObjectId> next = new LinkedHashSet<>();
            for (ObjectId id : frontier) {
                if (!resolved.add(id)) {
                    continue;
                }
                if (resolved.size() > TransferLimits.MAX_OBJECTS_PER_TRANSFER) {
                    throw new RemoteException(
                            "The fetch exceeds " + TransferLimits.MAX_OBJECTS_PER_TRANSFER
                                    + " objects");
                }
                next.addAll(references(id));
            }
            next.removeAll(resolved);
            frontier = next;
        }
        return received;
    }

    /** Requests absent objects in batches, verifying and storing each one. */
    private int request(Remote remote, List<ObjectId> absent) {
        int stored = 0;
        for (int start = 0; start < absent.size(); start += TransferLimits.MAX_IDS_PER_REQUEST) {
            List<String> batch = absent
                    .subList(start, Math.min(start + TransferLimits.MAX_IDS_PER_REQUEST, absent.size()))
                    .stream()
                    .map(ObjectId::toHex)
                    .toList();

            List<TransferredObject> delivered = transport.objects(remote, batch);
            if (delivered == null) {
                throw new RemoteException("The remote sent nothing for a batch of " + batch.size());
            }
            for (TransferredObject object : delivered) {
                VcsObject verified = object.verified();
                if (!objects.contains(verified.id())) {
                    objects.write(verified);
                    stored++;
                }
            }
            for (String id : batch) {
                if (!objects.contains(ObjectId.fromHex(id))) {
                    throw new RemoteException("The remote did not send object " + id);
                }
            }
        }
        return stored;
    }

    /** What one stored object points at. */
    private List<ObjectId> references(ObjectId id) {
        VcsObject object = objects.read(id).orElseThrow(() ->
                new RemoteException("Object " + id + " went missing during the fetch"));

        return switch (object) {
            case Commit commit -> {
                List<ObjectId> ids = new ArrayList<>(commit.parents());
                ids.add(commit.tree());
                yield ids;
            }
            case Tree tree -> tree.entries().stream().map(TreeEntry::id).toList();

            // A peer advertises branches only, so a fetch walk cannot legitimately
            // arrive at a tag: nothing in a branch's history names one. Reaching
            // one means the peer sent something a fetch never asked for, and it is
            // refused rather than followed.
            case Tag tag -> throw new RemoteException(
                    "The remote sent tag object " + tag.id()
                            + "; tags are not transferred between repositories");

            case Blob ignored -> List.of();
        };
    }

    private record Wanted(String branch, ObjectId commit) {
    }

    /**
     * What a fetch brought back.
     *
     * @param updatedRefs the tracking refs written, as {@code origin/main}
     * @param receivedObjects objects newly stored; zero when nothing had changed
     * @param skippedBranches names the remote offered that this repository will
     *     not track, kept rather than swallowed so an operator can see why a
     *     branch never appeared
     */
    public record Result(List<String> updatedRefs, int receivedObjects, List<String> skippedBranches) {

        public Result {
            updatedRefs = List.copyOf(updatedRefs);
            skippedBranches = List.copyOf(skippedBranches);
        }
    }
}
