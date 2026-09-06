package com.gitforge.vcsapi;

import com.gitforge.vcs.remote.Remote;
import com.gitforge.vcs.remote.RemoteException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A peer that answers with somewhere else to go.
 *
 * <p>{@code RemoteUrl} decides where this server may be pointed. A redirect is
 * the peer deciding instead, and the default behaviour made that reachable:
 * {@code SimpleClientHttpRequestFactory} calls
 * {@code setInstanceFollowRedirects("GET".equals(method))} — read out of the
 * shipped class, not guessed — and every read the transport performs is a GET.
 * So a remote on an entirely legitimate public host could answer a fetch with
 * {@code 302 Location: http://169.254.169.254/} and this server would go and ask.
 * The address guard was being applied to a URL the connection then stopped using.
 *
 * <p>A real socket rather than a mock, because the thing under test is what the
 * connection does with a status line — which a mocked client would decide for
 * itself and get right by assumption. A plain {@link ServerSocket} is enough and
 * needs no selector, which matters: this suite runs on machines where
 * {@code Selector.open()} cannot create its loopback pipe, and a test that only
 * runs elsewhere would not have caught this.
 *
 * <p>The assertion that matters is not the exception. A {@code RemoteException}
 * is thrown either way — an unreachable metadata service produces one just as a
 * refusal does — so a test that only checked for it would pass while the request
 * was being made. What is checked instead is whether the redirect target was
 * <em>reached</em>: a second socket stands in for the internal service and
 * records every connection, and it must record none.
 *
 * <p>That distinction was not theoretical. With the protection removed, this
 * test observed the client following a redirect twenty times in one call — the
 * JDK's own hop limit, reached rather than approached.
 */
class RemoteRedirectTest {

    private ServerSocket socket;
    private Thread server;
    private final List<String> requestedPaths = new CopyOnWriteArrayList<>();
    private volatile String response;

    /**
     * Stands in for whatever the redirect points at.
     *
     * <p>An internal service the server is not allowed to reach — the metadata
     * endpoint, a database admin page, anything on the far side of the address
     * guard. It answers nothing useful; it only records that it was contacted,
     * which is the whole question.
     */
    private ServerSocket internal;
    private Thread internalServer;
    private final List<String> internalHits = new CopyOnWriteArrayList<>();

    /** Private addresses are permitted here, or the loopback peer below is unreachable. */
    private final HttpRemoteTransport transport = new HttpRemoteTransport(true);

    @BeforeEach
    void startPeer() throws IOException {
        socket = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
        CountDownLatch ready = new CountDownLatch(1);

        server = new Thread(() -> {
            ready.countDown();
            while (!socket.isClosed()) {
                try (Socket client = socket.accept()) {
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                    String requestLine = in.readLine();
                    if (requestLine != null) {
                        requestedPaths.add(requestLine);
                    }
                    // Drain the headers so the client is not left writing into a
                    // socket nobody is reading.
                    String line;
                    while ((line = in.readLine()) != null && !line.isEmpty()) {
                        // headers are not what this test is about
                    }
                    OutputStream out = client.getOutputStream();
                    out.write(response.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException closing) {
                    return;
                }
            }
        });
        server.setDaemon(true);
        server.start();
        try {
            ready.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }

        internal = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
        internalServer = new Thread(() -> {
            while (!internal.isClosed()) {
                try (Socket caller = internal.accept()) {
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(caller.getInputStream(), StandardCharsets.UTF_8));
                    String line = in.readLine();
                    internalHits.add(line == null ? "<empty>" : line);
                    caller.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}"
                                    .getBytes(StandardCharsets.UTF_8));
                    caller.getOutputStream().flush();
                } catch (IOException closing) {
                    return;
                }
            }
        });
        internalServer.setDaemon(true);
        internalServer.start();
    }

    @AfterEach
    void stopPeer() throws IOException {
        socket.close();
        server.interrupt();
        internal.close();
        internalServer.interrupt();
    }

    /** Where the stand-in listens. Reachable, so failing to reach it proves a refusal. */
    private String internalUrl() {
        return "http://127.0.0.1:" + internal.getLocalPort() + "/latest/meta-data/";
    }

    private Remote peer() {
        return new Remote("origin", "http://127.0.0.1:" + socket.getLocalPort());
    }

    private static String redirectTo(String location) {
        return "HTTP/1.1 302 Found\r\nLocation: " + location + "\r\nContent-Length: 0\r\n\r\n";
    }

    @Test
    @DisplayName("a redirect to an internal service is refused, and that service is never contacted")
    void redirectToInternalServiceIsNotFollowed() {
        response = redirectTo(internalUrl());

        assertThatThrownBy(() -> transport.advertise(peer()))
                .isInstanceOf(RemoteException.class)
                .hasMessageContaining("redirect");

        assertThat(internalHits)
                .as("the whole point: the address the guard would have refused was never reached")
                .isEmpty();
        assertThat(requestedPaths)
                .as("exactly one request, the one that was asked for")
                .hasSize(1);
        assertThat(requestedPaths.getFirst()).contains("/remote-refs");
    }

    @Test
    @DisplayName("nor through a chain of redirects")
    void chainedRedirectIsNotFollowed() {
        // A peer that redirects to itself before redirecting onwards would defeat
        // a guard that only inspected the first hop.
        response = redirectTo("http://127.0.0.1:" + socket.getLocalPort() + "/hop");

        assertThatThrownBy(() -> transport.advertise(peer()))
                .isInstanceOf(RemoteException.class);

        assertThat(internalHits).isEmpty();
        assertThat(requestedPaths).hasSize(1);
    }

    @Test
    @DisplayName("a redirect back to the same peer is refused too")
    void redirectWithinThePeerIsNotFollowed() {
        // Even somewhere allowed. Following one redirect is following redirects,
        // and the rule is easier to keep than a rule about which ones are safe.
        response = redirectTo("http://127.0.0.1:" + socket.getLocalPort() + "/elsewhere");

        assertThatThrownBy(() -> transport.advertise(peer()))
                .isInstanceOf(RemoteException.class);

        assertThat(requestedPaths).hasSize(1);
    }

    @Test
    @DisplayName("every redirect status is refused, not only 302")
    void allRedirectStatusesRefused() {
        for (String status : List.of("301 Moved Permanently", "302 Found", "303 See Other",
                "307 Temporary Redirect", "308 Permanent Redirect")) {

            requestedPaths.clear();
            response = "HTTP/1.1 " + status + "\r\nLocation: " + internalUrl() + "\r\n"
                    + "Content-Length: 0\r\n\r\n";

            assertThatThrownBy(() -> transport.advertise(peer()))
                    .as(status)
                    .isInstanceOf(RemoteException.class);
            assertThat(requestedPaths).as(status).hasSize(1);
            assertThat(internalHits).as(status).isEmpty();
        }
    }

    @Test
    @DisplayName("object reads do not follow one either")
    void objectReadsAreNotRedirected() {
        // advertise, missing and objects are all GETs, and GET is exactly the
        // method the default followed. Each is checked rather than assumed to
        // share a code path.
        response = redirectTo(internalUrl());

        assertThatThrownBy(() -> transport.missing(peer(), List.of("a".repeat(40))))
                .isInstanceOf(RemoteException.class);
        assertThatThrownBy(() -> transport.objects(peer(), List.of("a".repeat(40))))
                .isInstanceOf(RemoteException.class);

        assertThat(internalHits).isEmpty();
        assertThat(requestedPaths).hasSize(2);
    }

    @Test
    @DisplayName("an ordinary answer is still read normally")
    void normalResponsesStillWork() {
        // The refusal must not have been bought by breaking the transport.
        String body = "{\"refs\":[{\"branch\":\"main\",\"commit\":\"" + "a".repeat(40) + "\"}]}";
        response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;

        List<com.gitforge.vcs.remote.RemoteTransport.RemoteBranch> branches =
                transport.advertise(peer());

        assertThat(branches).singleElement()
                .satisfies(branch -> assertThat(branch.branch()).isEqualTo("main"));
    }
}
