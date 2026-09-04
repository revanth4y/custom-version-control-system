package com.gitforge.vcs.remote;

import com.gitforge.vcs.object.Blob;
import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Tag;
import com.gitforge.vcs.object.Tree;
import com.gitforge.vcs.object.TreeEntry;
import com.gitforge.vcs.object.VcsObject;
import com.gitforge.vcs.ref.BranchName;
import com.gitforge.vcs.ref.RefStore;
import com.gitforge.vcs.repository.RepositoryLock;
import com.gitforge.vcs.storage.ObjectStore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Sending one branch of this repository to another.
 *
 * <p>The closure is computed here, from the local graph, because this side is the
 * one that has it. The peer is then asked which of those objects it lacks, so a
 * push carries what is needed rather than everything reachable — for a repository
 * whose history the peer already mostly holds, that is the difference between
 * sending a commit and sending a project.
 *
 * <p><strong>One branch per push.</strong> {@code RefStore} moves a single
 * reference atomically and has no all-or-nothing primitive for several. Rather
 * than build one before anything needs it, a push moves one branch, which makes
 * the existing atomic update exactly sufficient and removes the partially-applied
 * push as a possible outcome.
 *
 * <p><strong>The decision to accept is the peer's.</strong> Nothing here checks
 * whether the move is a fast-forward: this side cannot know the peer's current
 * tip without trusting what it was told, and a check made on the sender's word is
 * not a check. {@link ReceiveService} decides, inside the same lock in which it
 * moves the branch.
 */
public final class PushService {

    private final ObjectStore objects;
    private final RefStore refs;
    private final RemoteTransport transport;
    private final RepositoryLock lock;

    public PushService(
            ObjectStore objects, RefStore refs, RemoteTransport transport, RepositoryLock lock) {

        if (objects == null || refs == null || transport == null || lock == null) {
            throw new IllegalArgumentException(
                    "Pushing needs a store, references, a transport and a lock");
        }
        this.objects = objects;
        this.refs = refs;
        this.transport = transport;
        this.lock = lock;
    }

    /**
     * Sends {@code branch} to {@code remote}.
     *
     * @param token a bearer token the peer will accept. Used for this call and
     *     never written down: storing peer credentials is a separate problem with
     *     its own requirements, and a token in a repository file is a token
     *     nobody remembers is there
     * @throws RemoteException if the branch does not exist here, or the transfer
     *     fails
     * @throws NotFastForwardException if the peer refuses the move
     */
    public Result push(Remote remote, String branch, String token) {
        if (remote == null) {
            throw new RemoteException("A push needs a remote");
        }
        BranchName.validate(branch);

        // Read under the shared lock, so neither the walk nor the objects it
        // names can be collected midway through. That now spans the negotiation
        // as well, because the walk asks the peer what it needs as it goes; the
        // cost is that a collection here waits for the push, and the alternative
        // - deciding what to send and then reading it outside the lock - is a
        // push that fails because what it decided to send was swept in between.
        Negotiated negotiated = lock.reading(() -> negotiate(remote, branch));
        List<TransferredObject> payload = readIds(negotiated.wanted());

        try {
            return send(remote, token, branch, negotiated.tip(), payload);
        } catch (NotFastForwardException refused) {
            // A considered answer about the branch, not a gap in the history.
            throw refused;
        } catch (RemoteException failed) {
            if (!negotiated.pruned()) {
                // Nothing was skipped, so there is nothing a second attempt
                // could add. The failure is the answer.
                throw failed;
            }
            // The peer said it held an object and then would not take the push.
            // The likeliest reason is that it holds that object without holding
            // everything beneath it, which is a state this optimisation cannot
            // see and must not guess at. Send the whole closure once, exactly as
            // the unoptimised path would have, and let the peer decide again.
            Snapshot complete = lock.reading(() -> snapshot(branch));
            List<String> wanted = missingOnPeer(remote, complete.closure());
            return send(remote, token, branch, complete.tip(), read(wanted));
        }
    }

    /**
     * Finds where the two histories already agree, then walks only what is above
     * it.
     *
     * <p>The unoptimised walk enumerates the branch's entire history and then
     * asks the peer about all of it. For a repository whose history the peer
     * already has, that is the whole cost of the push: reading, inflating and
     * verifying every object locally, then a query for every thirty-two ids, to
     * be told that the one new commit is the only thing wanted.
     *
     * <p>So the peer is asked one question first. It advertises the commits its
     * branches point at; those are offered back to it, and the ones it confirms
     * holding become a boundary the local walk stops at. What remains is the part
     * of the history the peer plausibly lacks, which is then queried and sent as
     * before.
     *
     * <p><strong>Why not negotiate as the walk descends.</strong> That was tried
     * and is worse: a question per level makes the number of round trips
     * proportional to the depth of the history, so a first push of a long linear
     * history would take a request per commit and run out of rounds. Establishing
     * the boundary up front costs one request and leaves the walk local.
     *
     * <p><strong>What this does not assume.</strong> That the peer holding a
     * commit means it holds that commit's ancestors. It might not, and this walk
     * would then stop short. Nothing about the safety of the push rests on it
     * being right: the receiving side walks the proposed tip's closure over its
     * own disk, inside the same lock in which it moves the reference, and refuses
     * the move if anything is absent or damaged. A wrong guess costs a refused
     * push, which {@link #push} answers by sending the whole closure - never a
     * reference over a hole.
     */
    private Negotiated negotiate(Remote remote, String branch) {
        ObjectId tip = refs.getBranch(branch).orElseThrow(() ->
                new RemoteException("Branch does not exist: " + branch));

        Set<ObjectId> boundary = agreedBoundary(remote, branch);
        if (boundary.contains(tip)) {
            // The peer already holds this exact commit. Nothing to send; the
            // reference move alone is worth asking for, because the peer may not
            // have this branch under this name.
            return new Negotiated(tip, List.of(), true);
        }

        Set<ObjectId> wanted = new LinkedHashSet<>();
        Set<ObjectId> seen = new LinkedHashSet<>();
        Deque<ObjectId> pending = new ArrayDeque<>();
        pending.push(tip);

        boolean pruned = false;
        while (!pending.isEmpty()) {
            ObjectId id = pending.pop();
            if (boundary.contains(id)) {
                pruned = true;
                continue;
            }
            if (!seen.add(id)) {
                continue;
            }
            if (seen.size() > TransferLimits.MAX_OBJECTS_PER_TRANSFER) {
                throw new RemoteException(
                        "The branch history exceeds " + TransferLimits.MAX_OBJECTS_PER_TRANSFER
                                + " objects");
            }
            wanted.add(id);
            childrenOf(id).forEach(pending::push);
        }

        // Still asked, rather than assumed: the boundary narrows what has to be
        // offered, it does not decide what the peer needs.
        List<String> needed = missingOnPeer(remote, List.copyOf(wanted));
        return new Negotiated(
                tip, needed.stream().map(ObjectId::fromHex).toList(), pruned);
    }

    /**
     * The commits the peer says it holds, out of the ones it advertises.
     *
     * <p>One request for the advertisement and one to confirm, and the answer is
     * confirmed rather than taken from the advertisement: a branch naming a
     * commit is not the same claim as holding it.
     *
     * <p>The pushed branch is offered first and the list is capped at a single
     * request's worth, so a peer with thousands of branches costs the same two
     * questions as a peer with one. An empty answer is the ordinary first-push
     * case, and leaves the walk exactly as it was before this optimisation.
     */
    private Set<ObjectId> agreedBoundary(Remote remote, String branch) {
        List<RemoteTransport.RemoteBranch> advertised;
        try {
            advertised = transport.advertise(remote);
        } catch (RemoteException unavailable) {
            // A peer that will not say what it has is pushed to as if it had
            // nothing, which is correct and merely slower.
            return Set.of();
        }
        if (advertised == null || advertised.isEmpty()) {
            return Set.of();
        }

        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        advertised.stream()
                .filter(entry -> branch.equals(entry.branch()))
                .forEach(entry -> candidates.add(entry.commit()));
        for (RemoteTransport.RemoteBranch entry : advertised) {
            if (candidates.size() >= TransferLimits.MAX_IDS_PER_REQUEST) {
                break;
            }
            candidates.add(entry.commit());
        }

        List<String> offered = List.copyOf(candidates);
        List<String> absent = transport.missing(remote, offered);
        if (absent == null) {
            throw new RemoteException("The remote did not answer which objects it needs");
        }
        Set<String> lacking = new java.util.HashSet<>(absent);

        Set<ObjectId> held = new LinkedHashSet<>();
        for (String id : offered) {
            if (!lacking.contains(id)) {
                held.add(ObjectId.fromHex(id));
            }
        }
        return held;
    }

    /**
     * What one object needs beneath it.
     *
     * <p>The same rule the full walk applies, including its refusal to carry a
     * tag: branch history is commits, trees and blobs, and an object that should
     * be impossible here is refused rather than followed.
     */
    private List<ObjectId> childrenOf(ObjectId id) {
        VcsObject object = objects.read(id).orElseThrow(() ->
                new RemoteException("Local object " + id + " is missing; nothing was sent"));

        return switch (object) {
            case Commit commit -> {
                List<ObjectId> children = new ArrayList<>(commit.parents().size() + 1);
                children.add(commit.tree());
                children.addAll(commit.parents());
                yield children;
            }
            case Tree tree -> tree.entries().stream().map(TreeEntry::id).toList();
            case Tag tag -> throw new RemoteException(
                    "Local object " + tag.id() + " is a tag; tags are not transferred "
                            + "between repositories, so nothing was sent");
            case Blob ignored -> List.of();
        };
    }

    /**
     * The tip and everything beneath it, as it stands right now.
     *
     * <p>The complete walk, asking nobody anything. Kept because it is what the
     * fallback needs: when the peer refuses a push the boundary made shorter,
     * this is the version with nothing assumed.
     */
    private Snapshot snapshot(String branch) {
        ObjectId tip = refs.getBranch(branch).orElseThrow(() ->
                new RemoteException("Branch does not exist: " + branch));

        Set<ObjectId> closure = new LinkedHashSet<>();
        Deque<ObjectId> pending = new ArrayDeque<>();
        pending.push(tip);

        while (!pending.isEmpty()) {
            ObjectId id = pending.pop();
            if (!closure.add(id)) {
                continue;
            }
            if (closure.size() > TransferLimits.MAX_OBJECTS_PER_TRANSFER) {
                throw new RemoteException(
                        "The branch history exceeds " + TransferLimits.MAX_OBJECTS_PER_TRANSFER
                                + " objects");
            }
            childrenOf(id).forEach(pending::push);
        }
        return new Snapshot(tip, List.copyOf(closure));
    }

    /** Asks the peer, in batches, which ids it does not hold. */
    private List<String> missingOnPeer(Remote remote, List<ObjectId> closure) {
        List<String> missing = new ArrayList<>();
        List<String> ids = closure.stream().map(ObjectId::toHex).toList();

        for (int start = 0; start < ids.size(); start += TransferLimits.MAX_IDS_PER_REQUEST) {
            List<String> batch =
                    ids.subList(start, Math.min(start + TransferLimits.MAX_IDS_PER_REQUEST, ids.size()));
            List<String> answer = transport.missing(remote, batch);
            if (answer == null) {
                throw new RemoteException("The remote did not answer which objects it needs");
            }
            // Only ids that were actually offered. A peer asking for something it
            // was not offered is answering a different question.
            answer.stream().filter(batch::contains).forEach(missing::add);
        }
        return missing;
    }

    private List<TransferredObject> read(List<String> ids) {
        return readIds(ids.stream().map(ObjectId::fromHex).toList());
    }

    private List<TransferredObject> readIds(List<ObjectId> ids) {
        List<TransferredObject> payload = new ArrayList<>(ids.size());
        for (ObjectId id : ids) {
            VcsObject object = objects.read(id).orElseThrow(() ->
                    new RemoteException("Local object " + id + " is missing; nothing was sent"));
            payload.add(TransferredObject.of(object));
        }
        return payload;
    }

    /**
     * Sends the payload, asking for the branch move only on the final call.
     *
     * <p>Objects sent in an earlier batch are unreferenced on the peer until that
     * last call, so a collection there could remove them in between. That is
     * survivable and deliberately not defended against here: the peer verifies the
     * closure inside the same lock in which it moves the branch, so the outcome is
     * a refused push and a retry rather than a branch pointing at a gap.
     */
    private Result send(
            Remote remote, String token, String branch, ObjectId tip, List<TransferredObject> payload) {

        int stored = 0;
        int batches = Math.max(1, ceilingDivide(payload.size(), TransferLimits.MAX_OBJECTS_PER_BATCH));

        for (int index = 0; index < batches; index++) {
            int start = index * TransferLimits.MAX_OBJECTS_PER_BATCH;
            int end = Math.min(start + TransferLimits.MAX_OBJECTS_PER_BATCH, payload.size());
            List<TransferredObject> batch = start >= end ? List.of() : payload.subList(start, end);

            boolean last = index == batches - 1;
            RemoteTransport.ReceiveOutcome outcome = transport.receive(
                    remote,
                    token,
                    batch,
                    last ? branch : null,
                    last ? tip.toHex() : null);

            stored += outcome.storedObjects();
        }
        return new Result(branch, tip, payload.size(), stored);
    }

    private static int ceilingDivide(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private record Snapshot(ObjectId tip, List<ObjectId> closure) {
    }

    /**
     * The outcome of asking the peer what it needs.
     *
     * @param wanted the objects the peer said it lacks, in the order they were
     *     discovered
     * @param pruned whether anything was skipped because the peer said it had it;
     *     false means this walk saw the whole closure and there is no fuller
     *     attempt to fall back to
     */
    private record Negotiated(ObjectId tip, List<ObjectId> wanted, boolean pruned) {
    }

    /**
     * What a push did.
     *
     * @param branch the branch moved on the remote
     * @param commit where it now points
     * @param sentObjects objects the remote asked for and were sent
     * @param storedObjects objects the remote reported writing
     */
    public record Result(String branch, ObjectId commit, int sentObjects, int storedObjects) {
    }
}
