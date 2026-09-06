package com.gitforge.vcsapi;

import com.gitforge.vcs.remote.NotFastForwardException;
import com.gitforge.vcs.remote.Remote;
import com.gitforge.vcs.remote.RemoteException;
import com.gitforge.vcs.remote.RemoteTransport;
import com.gitforge.vcs.remote.RemoteUrl;
import com.gitforge.vcs.remote.TransferLimits;
import com.gitforge.vcs.remote.TransferredObject;
import com.gitforge.vcsapi.dto.MissingObjectsResponse;
import com.gitforge.vcsapi.dto.ReceiveObjectsRequest;
import com.gitforge.vcsapi.dto.ReceiveObjectsResponse;
import com.gitforge.vcsapi.dto.RemoteObjectsResponse;
import com.gitforge.vcsapi.dto.RemoteRefsResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;

/**
 * Talking to another GitForge server over HTTP.
 *
 * <p>Uses {@code RestClient} from {@code spring-web}, which is already on the
 * classpath — this milestone adds no dependency. It is also the first thing in
 * this application to make an outbound request at all, which is why every call
 * re-validates the remote's URL through {@link RemoteUrl} rather than trusting
 * that it was checked when the remote was registered. Configuration outlives the
 * check that admitted it, and a stored URL is only as safe as the last time
 * somebody looked at it.
 *
 * <p>Reads are GETs with ids in the query string, because that is what the peer's
 * security configuration makes anonymously available for a public repository —
 * the same shape every other read in this API has. Only the receive is a POST,
 * and only it carries a token.
 *
 * <p><strong>Redirects are not followed, and that is a security property rather
 * than a preference.</strong> {@link RemoteUrl} decides where this server may be
 * pointed, and a redirect is a way for the peer to decide instead. The default
 * behaviour made that reachable: {@code SimpleClientHttpRequestFactory} calls
 * {@code setInstanceFollowRedirects("GET".equals(method))}, which was read out of
 * the shipped class rather than assumed, and every read above is a GET. A remote
 * on a public host — validated, stored, entirely legitimate at registration —
 * could therefore answer a fetch with {@code 302 Location:
 * http://169.254.169.254/} and this server would issue that request. The address
 * guard was doing its work on a URL the connection then stopped using.
 *
 * <p>So the connection is told not to follow them, and a 3xx is reported as a
 * failed transfer. Refusing rather than re-validating and following is the
 * smaller and more defensible rule: re-validating each hop means resolving each
 * hop, which is where rebinding lives, and a GitForge peer has no reason to
 * redirect. The peer is told nothing it did not already know.
 */
@Component
public class HttpRemoteTransport implements RemoteTransport {

    /** How long to wait for a remote to accept a connection. */
    private static final java.time.Duration CONNECT_TIMEOUT = java.time.Duration.ofSeconds(10);

    /** How long to wait for it to answer once it has. */
    private static final java.time.Duration READ_TIMEOUT = java.time.Duration.ofSeconds(30);

    private final RestClient client;
    private final boolean allowPrivateAddresses;

    public HttpRemoteTransport(
            @Value("${vcs.remote.allow-private-addresses:false}") boolean allowPrivateAddresses) {
        this.allowPrivateAddresses = allowPrivateAddresses;

        // Timeouts on both halves, because a remote that accepts a connection and
        // then says nothing would otherwise hold a request thread for as long as it
        // liked - which is a denial of service a peer can perform by doing nothing.
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod)
                    throws IOException {
                super.prepareConnection(connection, httpMethod);
                // After super, which is where it is turned on for GET. Setting it
                // before would be overwritten and would look correct while doing
                // nothing, which is how the gap this closes came to exist.
                connection.setInstanceFollowRedirects(false);
            }
        };
        requests.setConnectTimeout(CONNECT_TIMEOUT);
        requests.setReadTimeout(READ_TIMEOUT);
        this.client = RestClient.builder().requestFactory(requests).build();
    }

    @Override
    public List<RemoteBranch> advertise(Remote remote) {
        RemoteRefsResponse response = get(remote, "/remote-refs", List.of(), RemoteRefsResponse.class);
        if (response == null || response.refs() == null) {
            return List.of();
        }
        return response.refs().stream()
                .map(ref -> new RemoteBranch(ref.branch(), ref.commit()))
                .toList();
    }

    @Override
    public List<String> missing(Remote remote, List<String> ids) {
        requireBoundedIds(ids);
        MissingObjectsResponse response =
                get(remote, "/remote-objects/missing", ids, MissingObjectsResponse.class);
        return response == null || response.missing() == null ? List.of() : response.missing();
    }

    @Override
    public List<TransferredObject> objects(Remote remote, List<String> ids) {
        requireBoundedIds(ids);
        RemoteObjectsResponse response =
                get(remote, "/remote-objects", ids, RemoteObjectsResponse.class);
        if (response == null || response.objects() == null) {
            return List.of();
        }
        return response.objects().stream()
                .map(entry -> new TransferredObject(entry.id(), entry.type(), entry.payload()))
                .toList();
    }

    @Override
    public ReceiveOutcome receive(
            Remote remote,
            String token,
            List<TransferredObject> objects,
            String branch,
            String commit) {

        if (token == null || token.isBlank()) {
            throw new RemoteException("Pushing to a remote needs a token it will accept");
        }
        List<RemoteObjectsResponse.ObjectEntry> entries = objects.stream()
                .map(object -> new RemoteObjectsResponse.ObjectEntry(
                        object.id(), object.type(), object.payload()))
                .toList();

        URI uri = URI.create(base(remote) + "/remote-objects/receive");
        try {
            ReceiveObjectsResponse response = client.post()
                    .uri(uri)
                    .header("Authorization", "Bearer " + token)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(new ReceiveObjectsRequest(entries, branch, commit))
                    .retrieve()
                    .onStatus(HttpRemoteTransport::isRefused, (request, failure) -> {
                        throw translate(failure.getStatusCode(), remote);
                    })
                    .body(ReceiveObjectsResponse.class);

            if (response == null) {
                throw new RemoteException("The remote gave no answer to a receive");
            }
            return new ReceiveOutcome(response.storedObjects(), response.branch(), response.commit());
        } catch (RemoteException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new RemoteException("Could not reach remote " + remote.name(), ex);
        }
    }

    private <T> T get(Remote remote, String path, List<String> ids, Class<T> type) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(base(remote) + path);
        ids.forEach(id -> builder.queryParam("id", id));
        URI uri = builder.build(true).toUri();

        try {
            return client.get()
                    .uri(uri)
                    .retrieve()
                    .onStatus(HttpRemoteTransport::isRefused, (request, failure) -> {
                        throw translate(failure.getStatusCode(), remote);
                    })
                    .body(type);
        } catch (RemoteException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new RemoteException("Could not reach remote " + remote.name(), ex);
        }
    }

    /**
     * The remote's base URL, re-validated on every use.
     *
     * <p>Trailing slashes are trimmed so the paths below concatenate predictably
     * rather than producing a double slash the peer may or may not tolerate.
     */
    private String base(Remote remote) {
        String url = RemoteUrl.validate(remote.url(), allowPrivateAddresses);
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static void requireBoundedIds(List<String> ids) {
        if (ids.size() > TransferLimits.MAX_IDS_PER_REQUEST) {
            throw new RemoteException(
                    "At most " + TransferLimits.MAX_IDS_PER_REQUEST + " ids may be requested at once");
        }
    }

    /**
     * Turns a peer's status into the failure it means here.
     *
     * <p>409 is the one a caller can act on — fetch, merge, push again — so it
     * keeps its own type. Everything else is a transfer that did not work, and
     * saying which status arrived is more useful than guessing why.
     */
    /**
     * Statuses that end the exchange.
     *
     * <p>3xx is in here because the connection no longer follows one. Without
     * this a redirect would arrive as a success with an empty body and be read as
     * a peer that answered with nothing, which is a confusing way to describe a
     * peer that answered with somewhere else to go.
     */
    private static boolean isRefused(HttpStatusCode status) {
        return status.is3xxRedirection() || status.isError();
    }

    private static RemoteException translate(HttpStatusCode status, Remote remote) {
        if (status.is3xxRedirection()) {
            return new RemoteException(
                    "Remote " + remote.name() + " redirected the request (" + status
                            + "); this server does not follow redirects, because the redirect"
                            + " target has not been checked against the rules a remote URL must"
                            + " satisfy. Point the remote at its final address instead.");
        }
        if (status.value() == 409) {
            return new NotFastForwardException(
                    "Remote " + remote.name() + " refused the push: it would not be a fast-forward");
        }
        if (status.value() == 401 || status.value() == 403) {
            return new RemoteException(
                    "Remote " + remote.name() + " refused the request: not authorised (" + status + ")");
        }
        return new RemoteException("Remote " + remote.name() + " answered " + status);
    }
}
