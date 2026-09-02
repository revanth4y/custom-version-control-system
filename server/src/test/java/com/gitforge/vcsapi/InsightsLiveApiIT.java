package com.gitforge.vcsapi;

import com.gitforge.TestcontainersConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.channels.Selector;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Insights API over a real socket.
 *
 * <p>Everything else exercises the application through MockMvc, which runs the
 * whole Spring stack but stops short of a network connection. This starts the
 * actual server on a real port and talks to it with an HTTP client, so the
 * serialization, the status codes and the query-string parsing are the ones a
 * browser would meet.
 *
 * <p>Safe by construction: a throwaway PostgreSQL container and a temporary
 * storage directory, both created for this class and discarded afterwards. It
 * touches no deployed runtime and no real repository data.
 *
 * <p><strong>It cannot run on every host.</strong> Some Windows environments
 * refuse {@code Selector.open()}, which an embedded server needs, and this one
 * does. There the class aborts with an explanation rather than failing red, and
 * the coverage is obtained on CI. That limitation is the reason
 * {@code RemoteCrossInstanceIT} carries the same guard.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gitforge.jwt.secret=live-qa-signing-secret-of-sufficient-length",
                "gitforge.cors.allowed-origins=http://localhost:5173"
        })
@Import(TestcontainersConfiguration.class)
class InsightsLiveApiIT {

    private static final Path STORAGE_ROOT = createStorageRoot();

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("gitforge.storage.root", () -> STORAGE_ROOT.toString());
    }

    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("gitforge-live-qa-storage");
        } catch (IOException ex) {
            throw new IllegalStateException("Could not create live QA storage root", ex);
        }
    }

    @BeforeAll
    static void requireWorkingSelectors() {
        try (Selector selector = Selector.open()) {
            Assumptions.assumeTrue(selector.isOpen());
        } catch (IOException ex) {
            Assumptions.abort(
                    "This host cannot open an NIO selector (" + ex.getMessage() + "), so an embedded "
                            + "server cannot be started here. Live API QA runs on CI instead.");
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private final RestTemplate http = new RestTemplate();
    private String token;
    private String base;

    @BeforeEach
    void seed() throws Exception {
        base = "http://127.0.0.1:" + port + "/api/v1";

        post("/auth/signup", """
                {"username":"octocat","email":"octocat@example.test","password":"correct horse battery"}
                """, null);

        JsonNode login = objectMapper.readTree(post("/auth/login", """
                {"username":"octocat","password":"correct horse battery"}
                """, null).getBody());
        token = login.get("token").asString();

        post("/repositories", """
                {"name":"demo","description":"live qa","visibility":"PUBLIC"}
                """, token);

        post("/repositories/octocat/demo/commits", """
                {"branch":"main","message":"First","changes":[
                  {"operation":"PUT","path":"a.txt","content":"1\\n"}]}
                """, token);

        post("/repositories/octocat/demo/commits", """
                {"branch":"main","message":"Second","changes":[
                  {"operation":"PUT","path":"a.txt","content":"2\\n"}]}
                """, token);

        post("/repositories/octocat/demo/tags", """
                {"name":"v1.0.0","target":"main","message":"Release 1.0"}
                """, token);

        post("/repositories/octocat/demo/releases", """
                {"tag":"v1.0.0","name":"Version 1.0","draft":false,"prerelease":false}
                """, token);
    }

    private ResponseEntity<String> post(String path, String body, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return http.exchange(base + path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode get(String path) throws Exception {
        return objectMapper.readTree(
                http.getForEntity(base + path, String.class).getBody());
    }

    private JsonNode getAs(String path, String bearer) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        return objectMapper.readTree(http.exchange(
                base + path, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody());
    }

    private static final String INSIGHTS = "/repositories/octocat/demo/insights";

    @Test
    @DisplayName("every Insights endpoint answers over HTTP with reconciling figures")
    void everyEndpointAnswersAndReconciles() throws Exception {
        JsonNode overview = get("/repositories/octocat/demo/insights");
        assertThat(overview.get("commits").asInt()).isEqualTo(2);

        JsonNode commits = get(INSIGHTS + "/commits");
        assertThat(commits.get("commits").asInt()).isEqualTo(2);
        assertThat(commits.get("merges").asInt() + commits.get("nonMerges").asInt())
                .isEqualTo(commits.get("commits").asInt());
        // The two endpoints share a root set and must not disagree.
        assertThat(commits.get("commits").asInt()).isEqualTo(overview.get("commits").asInt());

        JsonNode series = get(INSIGHTS + "/commits/series");
        assertThat(series.get("total").asInt()).isEqualTo(2);
        assertThat(series.get("points")).hasSize(365);

        JsonNode contributors = get(INSIGHTS + "/contributors");
        assertThat(contributors.get("total").asInt()).isEqualTo(1);

        JsonNode branches = get(INSIGHTS + "/branches");
        assertThat(branches.get("total").asInt()).isEqualTo(1);

        JsonNode refs = get(INSIGHTS + "/refs");
        assertThat(refs.get("branches").asInt() + refs.get("tags").asInt()
                + refs.get("remoteTrackingRefs").asInt())
                .isEqualTo(refs.get("total").asInt());

        JsonNode tags = get(INSIGHTS + "/tags");
        assertThat(tags.get("annotated").asInt() + tags.get("lightweight").asInt())
                .isEqualTo(tags.get("total").asInt());
        assertThat(tags.get("annotated").asInt()).isEqualTo(1);

        JsonNode releases = get(INSIGHTS + "/releases");
        assertThat(releases.get("published").asInt() + releases.get("drafts").asInt())
                .isEqualTo(releases.get("total").asInt());

        JsonNode issues = get(INSIGHTS + "/issues");
        assertThat(issues.get("open").asInt() + issues.get("closed").asInt())
                .isEqualTo(issues.get("total").asInt());

        JsonNode storage = get(INSIGHTS + "/storage");
        int counted = 0;
        long bytes = 0;
        for (JsonNode type : storage.get("byType")) {
            counted += type.get("count").asInt();
            bytes += type.get("bytes").asLong();
        }
        assertThat(counted).isEqualTo(storage.get("scannedObjects").asInt());
        assertThat(bytes).isEqualTo(storage.get("scannedBytes").asLong());

        JsonNode health = get(INSIGHTS + "/health");
        assertThat(health.get("scanned").asBoolean()).isFalse();
        assertThat(health.get("integrity").asString()).isEqualTo("NOT_VERIFIED");

        JsonNode scanned = get(INSIGHTS + "/health?scan=true");
        assertThat(scanned.get("scanned").asBoolean()).isTrue();
        assertThat(scanned.get("reachableObjects").asLong()
                + scanned.get("unreachableObjects").asInt())
                .isEqualTo(scanned.get("storedObjects").asLong());
    }

    @Test
    @DisplayName("the date-range boundary behaves the same over HTTP")
    void theRangeBoundaryHolds() {
        assertThat(http.getForEntity(
                base + INSIGHTS + "/commits/series?from=2026-01-01&to=2026-12-31", String.class)
                .getStatusCode().value()).isEqualTo(200);

        try {
            http.getForEntity(
                    base + INSIGHTS + "/commits/series?from=2026-01-01&to=2027-01-02", String.class);
            org.assertj.core.api.Assertions.fail("A 367-day range should have been refused");
        } catch (org.springframework.web.client.HttpClientErrorException ex) {
            assertThat(ex.getStatusCode().value()).isEqualTo(400);
        }
    }

    @Test
    @DisplayName("a draft release is invisible to an anonymous caller over HTTP")
    void draftsStayHidden() throws Exception {
        post("/repositories/octocat/demo/tags", """
                {"name":"v2.0.0","target":"main"}
                """, token);
        post("/repositories/octocat/demo/releases", """
                {"tag":"v2.0.0","name":"Unfinished","draft":true,"prerelease":false}
                """, token);

        JsonNode anonymous = get(INSIGHTS + "/releases");
        assertThat(anonymous.get("total").asInt()).isEqualTo(1);
        assertThat(anonymous.get("drafts").asInt()).isZero();

        JsonNode owner = getAs(INSIGHTS + "/releases", token);
        assertThat(owner.get("total").asInt()).isEqualTo(2);
        assertThat(owner.get("drafts").asInt()).isEqualTo(1);
    }
}
