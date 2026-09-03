package com.gitforge.cli.api;

import com.gitforge.cli.CliException;
import com.gitforge.cli.Context;
import com.gitforge.vcs.remote.Remote;
import com.gitforge.vcs.remote.RemoteException;
import com.gitforge.vcs.remote.RemoteTransport;
import com.gitforge.vcs.remote.TransferLimits;
import com.gitforge.vcs.remote.TransferredObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * The client half of the wire, for a command line rather than a server.
 *
 * <p>The engine already defines {@link RemoteTransport} and already contains the
 * transfer algorithms — what to ask for, in what order, and what to verify before
 * anything moves. Those are where the correctness lives and they are not
 * reimplemented here. This supplies only the part that was always going to be
 * environment-specific: how a request is made.
 *
 * <p>The server has its own implementation of this interface, built on Spring's
 * client. This one exists because the CLI must not depend on the server module —
 * a command line tool should not need a servlet container on its classpath — so
 * the two share the interface and the algorithms rather than the plumbing.
 *
 * <p>Limits are the engine's, not this class's. {@link TransferLimits} bounds
 * batch sizes and object counts, and honouring the same numbers is what keeps a
 * CLI push indistinguishable from a server-to-server one as far as the receiving
 * end is concerned.
 */
public final class CliRemoteTransport implements RemoteTransport {

    private final ApiClient api;
    private final Context context;

    public CliRemoteTransport(Context context) {
        this.context = context;
        this.api = new ApiClient(context);
    }

    @Override
    public List<RemoteBranch> advertise(Remote remote) {
        JsonNode response = api.get(pathFor(remote) + "/remote-refs");
        List<RemoteBranch> branches = new ArrayList<>();
        for (JsonNode row : arrayOf(response, "refs")) {
            branches.add(new RemoteBranch(
                    row.path("branch").asString(),
                    row.path("commit").asString()));
        }
        context.out().trace("remote advertises " + branches.size() + " branch(es)");
        return branches;
    }

    @Override
    public List<String> missing(Remote remote, List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        if (ids.size() > TransferLimits.MAX_IDS_PER_REQUEST) {
            throw new RemoteException(
                    "Asked about " + ids.size() + " objects at once; the limit is "
                            + TransferLimits.MAX_IDS_PER_REQUEST);
        }
        JsonNode response = api.get(pathFor(remote) + "/remote-objects/missing" + idQuery(ids));
        List<String> missing = new ArrayList<>();
        for (JsonNode row : arrayOf(response, "missing")) {
            missing.add(row.asString());
        }
        return missing;
    }

    @Override
    public List<TransferredObject> objects(Remote remote, List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        JsonNode response = api.get(pathFor(remote) + "/remote-objects" + idQuery(ids));
        List<TransferredObject> objects = new ArrayList<>();
        for (JsonNode row : arrayOf(response, "objects")) {
            objects.add(new TransferredObject(
                    row.path("id").asString(),
                    row.path("type").asString(),
                    row.path("payload").asString()));
        }
        return objects;
    }

    @Override
    public ReceiveOutcome receive(
            Remote remote,
            String token,
            List<TransferredObject> objects,
            String branch,
            String commit) {

        if (token == null || token.isBlank()) {
            throw new RemoteException(
                    "Pushing needs a token the remote will accept. Run 'gitforge auth login'.");
        }
        List<Map<String, Object>> payload = new ArrayList<>();
        for (TransferredObject object : objects) {
            payload.add(Map.of(
                    "id", object.id(),
                    "type", object.type(),
                    "payload", object.payload()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch", branch);
        body.put("commit", commit);
        body.put("objects", payload);

        JsonNode response = api.post(pathFor(remote) + "/remote-objects/receive", body);
        return new ReceiveOutcome(
                response.path("storedObjects").asInt(0),
                response.path("branch").asString(),
                response.path("commit").asString());
    }

    /**
     * The API path for a remote's repository.
     *
     * <p>A remote URL points at a repository on a server; the transfer endpoints
     * hang off that same path. Parsing it here rather than storing two fields
     * keeps a remote one piece of configuration.
     */
    private String pathFor(Remote remote) {
        String url = remote.url();
        int marker = url.indexOf("/repositories/");
        if (marker < 0) {
            throw new RemoteException(
                    "A remote URL must point at a repository, such as "
                            + "http://host/api/v1/repositories/owner/name — got " + url);
        }
        String tail = url.substring(marker);
        return tail.endsWith("/") ? tail.substring(0, tail.length() - 1) : tail;
    }

    /**
     * Object ids as a repeated {@code id} parameter.
     *
     * <p>Spring binds {@code ?id=a&id=b} to a list. Comma-joining them into one
     * value binds to a single-element list containing the comma-joined string,
     * which the server then reports as an unknown object rather than as a
     * malformed request — a failure that looks like missing data.
     */
    private static String idQuery(List<String> ids) {
        StringBuilder query = new StringBuilder();
        for (String id : ids) {
            query.append(query.isEmpty() ? '?' : '&').append("id=").append(ApiClient.segment(id));
        }
        return query.toString();
    }

    private static Iterable<JsonNode> arrayOf(JsonNode response, String field) {
        JsonNode array = response.path(field);
        if (!array.isArray()) {
            throw new RemoteException("The remote's reply had no '" + field + "' array");
        }
        return array;
    }

    /** Translates a transport failure into the CLI's vocabulary. */
    public static CliException translate(RuntimeException failure) {
        if (failure instanceof CliException already) {
            return already;
        }
        return CliException.remote(String.valueOf(failure.getMessage()));
    }
}
