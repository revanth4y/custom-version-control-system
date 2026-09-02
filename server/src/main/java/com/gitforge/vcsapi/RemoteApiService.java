package com.gitforge.vcsapi;

import com.gitforge.common.error.NotFoundException;
import com.gitforge.repo.Repo;
import com.gitforge.repo.RepoService;
import com.gitforge.user.User;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.VcsObject;
import com.gitforge.vcs.remote.FetchService;
import com.gitforge.vcs.remote.PushService;
import com.gitforge.vcs.remote.ReceiveService;
import com.gitforge.vcs.remote.Remote;
import com.gitforge.vcs.remote.RemoteException;
import com.gitforge.vcs.remote.RemoteStore;
import com.gitforge.vcs.remote.RemoteTransport;
import com.gitforge.vcs.remote.RemoteUrl;
import com.gitforge.vcs.remote.TransferLimits;
import com.gitforge.vcs.remote.TransferredObject;
import com.gitforge.vcs.repository.VcsRepository;
import com.gitforge.vcs.repository.VcsRepositoryFactory;
import com.gitforge.vcsapi.dto.CreateRemoteRequest;
import com.gitforge.vcsapi.dto.FetchResponse;
import com.gitforge.vcsapi.dto.MissingObjectsResponse;
import com.gitforge.vcsapi.dto.PushRequest;
import com.gitforge.vcsapi.dto.PushResponse;
import com.gitforge.vcsapi.dto.ReceiveObjectsRequest;
import com.gitforge.vcsapi.dto.ReceiveObjectsResponse;
import com.gitforge.vcsapi.dto.RemoteObjectsResponse;
import com.gitforge.vcsapi.dto.RemoteRefsResponse;
import com.gitforge.vcsapi.dto.RemoteResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Remote operations over HTTP.
 *
 * <p>Two kinds of endpoint meet here and they carry different authorization:
 *
 * <ul>
 *   <li><strong>What a peer may ask of us</strong> — advertisement, which objects
 *       we lack, and the objects themselves. All reads, all following
 *       {@link VcsRepositoryProvider#forRead}, so a private repository is as
 *       invisible to a peer as it is to a browser.
 *   <li><strong>What we do on our own repository</strong> — registering a remote,
 *       fetching, pushing, and accepting a push. All writes, all owner-only
 *       through {@link VcsRepositoryProvider#forWrite}.
 * </ul>
 *
 * <p>The engine decides what is safe; this layer decides who may ask. Nothing
 * here re-implements reachability, verification or the fast-forward rule — those
 * live in {@code vcs.remote}, where the object model they reason about is.
 */
@Service
public class RemoteApiService {

    private final VcsRepositoryProvider repositories;
    private final RepoService repoService;
    private final VcsRepositoryFactory factory;
    private final RemoteTransport transport;
    private final boolean allowPrivateAddresses;

    public RemoteApiService(
            VcsRepositoryProvider repositories,
            RepoService repoService,
            VcsRepositoryFactory factory,
            RemoteTransport transport,
            @Value("${vcs.remote.allow-private-addresses:false}") boolean allowPrivateAddresses) {

        this.repositories = repositories;
        this.repoService = repoService;
        this.factory = factory;
        this.transport = transport;
        this.allowPrivateAddresses = allowPrivateAddresses;
    }

    // ---- What a peer may ask of us -------------------------------------------

    /**
     * Every branch and its tip.
     *
     * <p>Branches only: HEAD says what this repository has checked out, and its
     * own remote-tracking refs are its record of a third party. Neither is a peer's
     * business, and passing the second on would let one peer's view of the world
     * propagate as though it were ours.
     */
    public RemoteRefsResponse advertise(String owner, String name, User viewer) {
        VcsRepository repository = repositories.forRead(owner, name, viewer);
        List<RemoteRefsResponse.RefEntry> refs = new ArrayList<>();
        for (String branch : repository.branches().listBranches()) {
            repository.branches().getBranch(branch)
                    .ifPresent(tip -> refs.add(new RemoteRefsResponse.RefEntry(branch, tip.toHex())));
        }
        return new RemoteRefsResponse(refs);
    }

    /** Which of the offered ids this repository does not hold. */
    public MissingObjectsResponse missing(String owner, String name, User viewer, List<String> ids) {
        VcsRepository repository = repositories.forRead(owner, name, viewer);
        List<String> missing = new ArrayList<>();
        for (ObjectId id : parse(ids)) {
            if (!repository.objects().contains(id)) {
                missing.add(id.toHex());
            }
        }
        return new MissingObjectsResponse(missing);
    }

    /**
     * The named objects, for a peer that asked by id.
     *
     * <p>An id naming nothing is simply absent from the answer. The caller must
     * compare what it asked for against what came back regardless, in order to
     * know the transfer is complete, so a 404 here would only make the common case
     * — asking for something already collected — harder to handle.
     */
    public RemoteObjectsResponse objects(String owner, String name, User viewer, List<String> ids) {
        VcsRepository repository = repositories.forRead(owner, name, viewer);
        List<RemoteObjectsResponse.ObjectEntry> entries = new ArrayList<>();
        for (ObjectId id : parse(ids)) {
            repository.objects().read(id).ifPresent(object -> entries.add(entry(object)));
        }
        return new RemoteObjectsResponse(entries);
    }

    /** Accepts objects, and a branch move, from a peer. Owner-only. */
    public ReceiveObjectsResponse receive(
            String owner, String name, User viewer, ReceiveObjectsRequest request) {

        VcsRepository repository = repositories.forWrite(owner, name, viewer);
        List<TransferredObject> objects = request.objects() == null
                ? List.of()
                : request.objects().stream()
                        .map(entry -> new TransferredObject(entry.id(), entry.type(), entry.payload()))
                        .toList();

        ObjectId commit = null;
        if (request.commit() != null && !request.commit().isBlank()) {
            try {
                commit = ObjectId.fromHex(request.commit());
            } catch (IllegalArgumentException ex) {
                throw new RemoteException("Malformed commit id: " + request.commit(), ex);
            }
        }

        ReceiveService.Result result = repository.receives().receive(objects, request.branch(), commit);
        return new ReceiveObjectsResponse(
                result.storedObjects(),
                result.branch(),
                result.commit() == null ? null : result.commit().toHex());
    }

    // ---- What we do on our own repository ------------------------------------

    /** The remotes this repository knows about. */
    public List<RemoteResponse> list(String owner, String name, User viewer) {
        repositories.forRead(owner, name, viewer);
        return remoteStore(owner, name, viewer).list().stream().map(RemoteResponse::from).toList();
    }

    /** Registers a remote, or re-points an existing one. Owner-only. */
    public RemoteResponse register(
            String owner, String name, User viewer, CreateRemoteRequest request) {

        repositories.forWrite(owner, name, viewer);
        String url = RemoteUrl.validate(request.url(), allowPrivateAddresses);
        Remote remote = new Remote(request.name(), url);
        remoteStore(owner, name, viewer).save(remote);
        return RemoteResponse.from(remote);
    }

    /**
     * Forgets a remote. Owner-only.
     *
     * <p>Its tracking refs and their objects stay exactly where they are. Removing
     * a name is not a request to reclaim storage, which is the distinction branch
     * deletion has always kept and which garbage collection exists to serve.
     */
    public void forget(String owner, String name, User viewer, String remote) {
        repositories.forWrite(owner, name, viewer);
        if (!remoteStore(owner, name, viewer).delete(remote)) {
            throw new NotFoundException("No such remote: " + remote);
        }
    }

    /** Fetches from a registered remote into remote-tracking refs. Owner-only. */
    public FetchResponse fetch(String owner, String name, User viewer, String remoteName) {
        VcsRepository repository = repositories.forWrite(owner, name, viewer);
        Remote remote = requireRemote(owner, name, viewer, remoteName);

        FetchService fetches = new FetchService(
                repository.objects(), repository.refs(), transport, repository.lock());
        FetchService.Result result = fetches.fetch(remote);

        return new FetchResponse(
                result.updatedRefs(), result.receivedObjects(), result.skippedBranches());
    }

    /** Pushes one branch to a registered remote. Owner-only. */
    public PushResponse push(
            String owner, String name, User viewer, String remoteName, PushRequest request) {

        VcsRepository repository = repositories.forWrite(owner, name, viewer);
        Remote remote = requireRemote(owner, name, viewer, remoteName);

        PushService pushes = new PushService(
                repository.objects(), repository.refs(), transport, repository.lock());
        PushService.Result result = pushes.push(remote, request.branch(), request.token());

        return new PushResponse(
                result.branch(),
                result.commit().toHex(),
                result.sentObjects(),
                result.storedObjects());
    }

    // ---- Shared -------------------------------------------------------------

    private Remote requireRemote(String owner, String name, User viewer, String remoteName) {
        return remoteStore(owner, name, viewer).get(remoteName)
                .orElseThrow(() -> new NotFoundException("No such remote: " + remoteName));
    }

    /**
     * The remotes file for one repository.
     *
     * <p>Built from {@link VcsRepositoryFactory#pathFor}, which is the only place
     * an id becomes a path and carries the guard that keeps it inside the storage
     * root. Resolving it here rather than caching it means a repository opened
     * before a remote was registered still sees it.
     */
    private RemoteStore remoteStore(String owner, String name, User viewer) {
        Repo repo = repoService.requireReadable(owner, name, viewer);
        Path root = factory.pathFor(VcsRepositoryProvider.storageIdOf(repo));
        return new RemoteStore(root);
    }

    private static List<ObjectId> parse(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        if (ids.size() > TransferLimits.MAX_IDS_PER_REQUEST) {
            throw new RemoteException(
                    "At most " + TransferLimits.MAX_IDS_PER_REQUEST + " ids may be requested at once");
        }
        List<ObjectId> parsed = new ArrayList<>(ids.size());
        for (String id : ids) {
            try {
                parsed.add(ObjectId.fromHex(id));
            } catch (IllegalArgumentException ex) {
                throw new RemoteException("Malformed object id: " + id, ex);
            }
        }
        return parsed;
    }

    private static RemoteObjectsResponse.ObjectEntry entry(VcsObject object) {
        TransferredObject transferred = TransferredObject.of(object);
        return new RemoteObjectsResponse.ObjectEntry(
                transferred.id(), transferred.type(), transferred.payload());
    }
}
