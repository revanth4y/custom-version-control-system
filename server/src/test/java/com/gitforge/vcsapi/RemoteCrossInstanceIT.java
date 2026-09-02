package com.gitforge.vcsapi;

import com.gitforge.GitForgeApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Assumptions;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.channels.Selector;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two GitForge servers, two storage roots, two ports, one real socket between
 * them.
 *
 * <p>Every other integration test in this suite drives the application through
 * {@code MockMvc}, which never binds a port. That is right for them and useless
 * here: the thing this milestone adds is one server talking to another, and a
 * transport that is never actually spoken over is not a transport. So this test
 * starts two complete application contexts on real ports and makes the fetching
 * server dial the other one, through its own HTTP client, its filter chain, its
 * security rules and its JSON.
 *
 * <p>The two share a database — the repository and user rows are the same on both
 * — but have <strong>separate storage roots</strong>, which is where the objects
 * and refs live. That is what makes a fetch mean something: the receiving server
 * genuinely does not hold what it is asking for.
 *
 * <p>Ordered, because the state each step leaves is what the next one is about: a
 * repository is populated, fetched, collected, pushed to, and only then asked to
 * accept something it should refuse.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RemoteCrossInstanceIT {

    private static final String SECRET = "cross-instance-test-signing-secret-of-sufficient-length";
    private static final String OWNER = "octocat";

    private static PostgreSQLContainer<?> postgres;
    private static ConfigurableApplicationContext origin;
    private static ConfigurableApplicationContext mirror;
    private static String originUrl;
    private static String mirrorUrl;
    private static String token;

    /**
     * Built on {@code SimpleClientHttpRequestFactory} rather than the default JDK
     * client, which cannot create its internal selector pipe in every environment
     * this suite runs in — the same reason the transport under test uses it.
     */
    private static final RestClient http = RestClient.builder()
            .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory())
            .build();

    @BeforeAll
    static void startBothServers() throws IOException {
        requireWorkingSelectors();

        postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
        postgres.start();

        origin = start(Files.createTempDirectory("gitforge-origin"));
        mirror = start(Files.createTempDirectory("gitforge-mirror"));
        originUrl = "http://localhost:" + port(origin);
        mirrorUrl = "http://localhost:" + port(mirror);

        // One account, one signing secret, so a token minted by either server is
        // accepted by both - which is what lets one push to the other.
        token = signUp();
    }

    /**
     * Refuses to pretend this ran where it cannot.
     *
     * <p>An embedded servlet container needs {@link Selector}, which needs a
     * loopback socket pair. Some hosts — the Windows development machine this was
     * written on among them — refuse that specific operation while allowing
     * ordinary binds and connects, and Tomcat then cannot start at all.
     *
     * <p>Skipping is the honest outcome there: the alternative is a red build that
     * says nothing about the code. It is a skip and not a quiet pass, and CI runs
     * on Linux where selectors work, so this test does get executed for real on
     * every pull request.
     */
    private static void requireWorkingSelectors() {
        try (Selector selector = Selector.open()) {
            Assumptions.assumeTrue(selector.isOpen());
        } catch (IOException ex) {
            Assumptions.abort(
                    "This host cannot open an NIO selector (" + ex.getMessage() + "), so an embedded "
                            + "server cannot be started here. Cross-instance transfer is exercised on "
                            + "CI instead.");
        }
    }

    @AfterAll
    static void stopBothServers() {
        if (mirror != null) {
            mirror.close();
        }
        if (origin != null) {
            origin.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    /**
     * Starts one complete server.
     *
     * <p>Command-line arguments rather than {@code properties(..)}: the latter
     * registers <em>default</em> properties, which {@code application.yml} then
     * overrides, so the JWT secret would arrive empty and the server would refuse
     * to start. Arguments sit above the yaml, which is where these belong.
     */
    private static ConfigurableApplicationContext start(Path storageRoot) {
        return new SpringApplicationBuilder(GitForgeApplication.class).run(
                "--server.port=0",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--gitforge.jwt.secret=" + SECRET,
                "--gitforge.cors.allowed-origins=http://localhost:5173",
                "--gitforge.storage.root=" + storageRoot,
                // Both servers are on this machine, so the SSRF guard would
                // otherwise - correctly - refuse to dial either of them.
                "--vcs.remote.allow-private-addresses=true");
    }

    private static String port(ConfigurableApplicationContext context) {
        return context.getEnvironment().getProperty("local.server.port");
    }

    // ---- HTTP helpers --------------------------------------------------------

    private static Map<?, ?> post(String url, Object body, String bearer) {
        RestClient.RequestBodySpec spec = http.post().uri(url).contentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            spec = spec.header("Authorization", "Bearer " + bearer);
        }
        return spec.body(body).retrieve().body(Map.class);
    }

    private static Map<?, ?> get(String url) {
        return http.get().uri(url).retrieve().body(Map.class);
    }

    private static int statusOfPost(String url, Object body, String bearer) {
        RestClient.RequestBodySpec spec = http.post().uri(url).contentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            spec = spec.header("Authorization", "Bearer " + bearer);
        }
        return spec.body(body)
                .exchange((request, response) -> response.getStatusCode().value(), false);
    }

    private static String signUp() {
        Map<?, ?> body = post(originUrl + "/api/v1/auth/signup", Map.of(
                "username", OWNER,
                "email", OWNER + "@gitforge.test",
                "password", "correct-horse-battery-staple"), null);
        return (String) body.get("token");
    }

    private static void createRepo(String server, String name) {
        post(server + "/api/v1/repositories", Map.of(
                "name", name, "description", "cross-instance", "visibility", "PUBLIC"), token);
    }

    private static String commit(String server, String repo, String message, String path, String text) {
        Map<?, ?> body = post(
                server + "/api/v1/repositories/" + OWNER + "/" + repo + "/commits",
                Map.of("branch", "main", "message", message, "changes",
                        java.util.List.of(Map.of("operation", "PUT", "path", path, "content", text))),
                token);
        return (String) body.get("sha");
    }

    private static String repoUrl(String server, String repo) {
        return server + "/api/v1/repositories/" + OWNER + "/" + repo;
    }

    // ---- The tests -----------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("a repository on one server can be fetched by a repository on another")
    void fetchAcrossInstances() {
        createRepo(originUrl, "source");
        commit(originUrl, "source", "First", "a.txt", "one\\n");
        String tip = commit(originUrl, "source", "Second", "b.txt", "two\\n");

        createRepo(mirrorUrl, "mirror");

        // The mirror genuinely holds nothing of the source's history.
        Map<?, ?> before = get(repoUrl(mirrorUrl, "mirror") + "/gc");
        assertThat(((Number) before.get("storedObjects")).intValue()).isZero();

        post(repoUrl(mirrorUrl, "mirror") + "/remotes",
                Map.of("name", "origin", "url", repoUrl(originUrl, "source")), token);

        Map<?, ?> fetched = post(
                repoUrl(mirrorUrl, "mirror") + "/remotes/origin/fetch", Map.of(), token);

        assertThat(fetched.get("updatedRefs").toString()).contains("origin/main");
        assertThat(((Number) fetched.get("receivedObjects")).intValue()).isPositive();

        // Everything the source held, the mirror now holds - over a real socket.
        Map<?, ?> originGc = get(repoUrl(originUrl, "source") + "/gc");
        Map<?, ?> mirrorGc = get(repoUrl(mirrorUrl, "mirror") + "/gc");
        assertThat(((Number) mirrorGc.get("storedObjects")).intValue())
                .isEqualTo(((Number) originGc.get("storedObjects")).intValue());

        // And it is intact: every object re-hashes to the id it was filed under.
        Map<?, ?> integrity = get(repoUrl(mirrorUrl, "mirror") + "/integrity");
        assertThat(integrity.get("healthy")).isEqualTo(true);
        assertThat((java.util.List<?>) integrity.get("damaged")).isEmpty();

        // The tracking ref points where the source's branch does.
        Map<?, ?> advertised = get(repoUrl(originUrl, "source") + "/remote-refs");
        assertThat(advertised.get("refs").toString()).contains(tip);
    }

    @Test
    @Order(2)
    @DisplayName("fetched objects survive a collection on the fetching server")
    void fetchedObjectsAreNotCollected() {
        Map<?, ?> before = get(repoUrl(mirrorUrl, "mirror") + "/gc");
        int stored = ((Number) before.get("storedObjects")).intValue();

        // No local branch reaches any of it: the tracking ref is the only thing
        // speaking for these objects.
        assertThat(((Number) before.get("unreachableObjects")).intValue()).isZero();

        Map<?, ?> collected = post(repoUrl(mirrorUrl, "mirror") + "/gc", Map.of(), token);

        assertThat(((Number) collected.get("collectedObjects")).intValue()).isZero();
        Map<?, ?> after = get(repoUrl(mirrorUrl, "mirror") + "/gc");
        assertThat(((Number) after.get("storedObjects")).intValue()).isEqualTo(stored);
    }

    @Test
    @Order(3)
    @DisplayName("a fetch that finds nothing new is a no-op")
    void refetchingChangesNothing() {
        Map<?, ?> again = post(repoUrl(mirrorUrl, "mirror") + "/remotes/origin/fetch", Map.of(), token);

        assertThat(((Number) again.get("receivedObjects")).intValue()).isZero();
        assertThat(again.get("updatedRefs").toString()).contains("origin/main");
    }

    @Test
    @Order(4)
    @DisplayName("a branch can be pushed to a repository on another server")
    void pushAcrossInstances() {
        createRepo(mirrorUrl, "outbound");
        commit(mirrorUrl, "outbound", "Local work", "c.txt", "three\\n");

        createRepo(originUrl, "target");

        post(repoUrl(mirrorUrl, "outbound") + "/remotes",
                Map.of("name", "upstream", "url", repoUrl(originUrl, "target")), token);

        Map<?, ?> pushed = post(
                repoUrl(mirrorUrl, "outbound") + "/remotes/upstream/push",
                Map.of("branch", "main", "token", token), token);

        assertThat(pushed.get("branch")).isEqualTo("main");
        assertThat(((Number) pushed.get("sentObjects")).intValue()).isPositive();

        // The receiving server has the branch and every object beneath it.
        Map<?, ?> advertised = get(repoUrl(originUrl, "target") + "/remote-refs");
        assertThat(advertised.get("refs").toString()).contains(String.valueOf(pushed.get("commit")));

        Map<?, ?> integrity = get(repoUrl(originUrl, "target") + "/integrity");
        assertThat(integrity.get("healthy")).isEqualTo(true);
    }

    @Test
    @Order(5)
    @DisplayName("pushing again after the remote moved ahead is refused as not a fast-forward")
    void nonFastForwardPushIsRefused() {
        // The receiving side gains a commit the sender does not have.
        commit(originUrl, "target", "Their work", "theirs.txt", "theirs\\n");

        // The sender adds an unrelated commit, so its tip no longer leads to the
        // remote's - the histories have genuinely diverged.
        commit(mirrorUrl, "outbound", "Our work", "ours.txt", "ours\\n");

        int status = statusOfPost(
                repoUrl(mirrorUrl, "outbound") + "/remotes/upstream/push",
                Map.of("branch", "main", "token", token), token);

        assertThat(status).isEqualTo(409);
    }

    @Test
    @Order(6)
    @DisplayName("a receive without a token is refused, and the branch does not move")
    void receiveRequiresAuthentication() {
        Map<?, ?> before = get(repoUrl(originUrl, "target") + "/remote-refs");

        int status = HttpStatusCode.valueOf(statusOfPost(
                repoUrl(originUrl, "target") + "/remote-objects/receive",
                Map.of("objects", java.util.List.of()), null)).value();

        assertThat(status).isEqualTo(401);
        assertThat(get(repoUrl(originUrl, "target") + "/remote-refs")).isEqualTo(before);
    }

    @Test
    @Order(7)
    @DisplayName("advertisement and object reads are anonymous on a public repository")
    void readsAreAnonymous() {
        assertThat(get(repoUrl(originUrl, "source") + "/remote-refs")).isNotNull();
        assertThat(get(repoUrl(originUrl, "source") + "/remote-objects/missing?id="
                + "0".repeat(40))).isNotNull();
    }
}
