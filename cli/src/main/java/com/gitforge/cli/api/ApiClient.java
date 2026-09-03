package com.gitforge.cli.api;

import com.gitforge.cli.CliException;
import com.gitforge.cli.Context;
import com.gitforge.cli.ExitCode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * How the CLI talks to a GitForge server.
 *
 * <p>{@code java.net.http} rather than a client library: the server speaks
 * ordinary JSON over HTTPS, the CLI makes one request at a time, and adding a
 * dependency to save a dozen lines would put a second HTTP stack in a tool whose
 * whole argument is that it carries nothing it does not need.
 *
 * <p>Three rules, and the first two are the reason this class exists at all
 * rather than being inlined.
 *
 * <p><strong>The token goes in a header and nowhere else.</strong> Never in the
 * URL, never in a query parameter, never in {@code argv}. A URL with credentials
 * ends up in shell history, in server access logs and in the text of error
 * messages; a header does not.
 *
 * <p><strong>Server error codes become CLI exit codes.</strong> One table, so a
 * 403 from the server and a local refusal both exit 4 and a script has one
 * vocabulary to learn. The server's own {@code code} is preserved rather than
 * re-derived from the status, because the server is more specific than HTTP is.
 *
 * <p><strong>A 404 is passed through unchanged.</strong> The server answers 404
 * for a private repository the caller may not see, so that "exists but
 * forbidden" and "does not exist" cannot be told apart. The CLI must not
 * helpfully distinguish them.
 */
public final class ApiClient {

    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final String base;
    private final Context context;

    public ApiClient(Context context) {
        this.context = context;
        this.base = trimTrailingSlash(baseUrl(context));
        try {
            this.http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(Math.min(30, context.options().timeoutSeconds())))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
        } catch (RuntimeException | Error unavailable) {
            // Java's HTTP client needs an NIO selector, and some hosts refuse to
            // open one — a Windows machine whose firewall blocks the loopback
            // socket pair the JDK uses will fail here before any request is made.
            // Reporting that as a transport failure, with the cause named, is far
            // more useful than an internal error the caller cannot act on.
            throw new CliException("REMOTE_TRANSFER_FAILED", ExitCode.REMOTE_TRANSFER,
                    "Could not start an HTTP client on this host: " + unavailable.getMessage()
                            + ". Local commands still work; anything needing the server does not.");
        }
    }

    /**
     * Where the server is.
     *
     * <p>No default guess at a hostname: a CLI that silently talks to
     * {@code localhost} when it was meant to talk to a real server is a CLI that
     * reports an empty repository list and lets you believe it.
     */
    private static String baseUrl(Context context) {
        String fromEnv = context.environment().get("GITFORGE_API_URL");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return context.config().get("api.url").orElseThrow(() -> CliException.usage(
                "No server configured. Run 'gitforge config set api.url <url>' "
                        + "or set GITFORGE_API_URL."));
    }

    /** The host part, used as the key credentials are stored under. */
    public String host() {
        return URI.create(base).getHost() + portSuffix(URI.create(base));
    }

    private static String portSuffix(URI uri) {
        return uri.getPort() < 0 ? "" : ":" + uri.getPort();
    }

    public JsonNode get(String path) {
        return send(request(path).GET().build());
    }

    public JsonNode post(String path, Object body) {
        return send(request(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(write(body), StandardCharsets.UTF_8))
                .build());
    }

    public JsonNode patch(String path, Object body) {
        return send(request(path)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(write(body), StandardCharsets.UTF_8))
                .build());
    }

    public JsonNode delete(String path) {
        return send(request(path).DELETE().build());
    }

    /** Percent-encodes one path segment, so a name with a slash cannot change the route. */
    public static String segment(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(Duration.ofSeconds(context.options().timeoutSeconds()))
                .header("Accept", "application/json");
        // Anonymous requests are legitimate: public repositories are readable
        // without a token, and sending one that does not exist would be worse
        // than sending none.
        Optional<String> token = context.credentials().tokenFor(host());
        token.ifPresent(value -> builder.header("Authorization", "Bearer " + value));
        context.out().trace((token.isPresent() ? "authenticated " : "anonymous ") + "request to " + path);
        return builder;
    }

    private JsonNode send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpTimeoutException tooSlow) {
            throw new CliException("TIMEOUT", ExitCode.TIMEOUT,
                    "The server did not answer within " + context.options().timeoutSeconds() + " seconds");
        } catch (IOException unreachable) {
            throw new CliException("REMOTE_TRANSFER_FAILED", ExitCode.REMOTE_TRANSFER,
                    "Could not reach " + base + ": " + unreachable.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw CliException.failure("Interrupted while waiting for the server");
        }

        int status = response.statusCode();
        String body = response.body();
        if (status >= 200 && status < 300) {
            return body == null || body.isBlank() ? json.createObjectNode() : read(body);
        }
        throw translate(status, body);
    }

    /**
     * A server failure, in the CLI's vocabulary.
     *
     * <p>The server's own {@code code} is kept — it distinguishes cases HTTP
     * cannot, such as a conflict caused by a duplicate name from one caused by a
     * non-fast-forward — and only the exit status is derived from the status
     * line.
     */
    private CliException translate(int status, String body) {
        String code = "HTTP_" + status;
        String message = "The server returned " + status;
        try {
            JsonNode error = read(body);
            if (error.has("code")) {
                code = error.get("code").asString();
            }
            if (error.has("message")) {
                message = error.get("message").asString();
            }
            if (error.has("fieldErrors") && error.get("fieldErrors").isArray()) {
                StringBuilder detail = new StringBuilder(message);
                for (JsonNode field : error.get("fieldErrors")) {
                    detail.append("; ").append(field.path("field").asString())
                            .append(' ').append(field.path("message").asString());
                }
                message = detail.toString();
            }
        } catch (RuntimeException notJson) {
            // A non-JSON body from a proxy or a crash. The status still tells us
            // enough to choose an exit code.
        }

        ExitCode exit = switch (status) {
            case 400, 422 -> ExitCode.USAGE;
            case 401 -> ExitCode.FORBIDDEN;
            case 403 -> ExitCode.FORBIDDEN;
            // Passed through as-is: the server answers 404 for a private
            // repository on purpose, and the CLI must not resolve that ambiguity.
            case 404 -> ExitCode.NOT_FOUND;
            case 409 -> ExitCode.CONFLICT;
            case 408, 504 -> ExitCode.TIMEOUT;
            case 413, 429 -> ExitCode.REFUSED;
            default -> status >= 500 ? ExitCode.FAILURE : ExitCode.REMOTE_TRANSFER;
        };
        return new CliException(code, exit, message);
    }

    private JsonNode read(String body) {
        try {
            return json.readTree(body);
        } catch (RuntimeException malformed) {
            throw new CliException("MALFORMED_RESPONSE", ExitCode.REMOTE_TRANSFER,
                    "The server's reply was not valid JSON");
        }
    }

    private String write(Object body) {
        return json.writeValueAsString(body);
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    // ------------------------------------------------------------ conversion

    /** A JSON node as plain maps and lists, so it can go straight into the envelope. */
    public static Object plain(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            node.propertyStream().forEach(entry -> map.put(entry.getKey(), plain(entry.getValue())));
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new java.util.ArrayList<>();
            node.forEach(element -> list.add(plain(element)));
            return list;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isInt() || node.isLong()) {
            return node.asLong();
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        return node.asString();
    }
}
