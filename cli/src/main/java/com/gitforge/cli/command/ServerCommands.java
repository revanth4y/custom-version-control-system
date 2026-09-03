package com.gitforge.cli.command;

import com.gitforge.cli.CliException;
import com.gitforge.cli.Context;
import com.gitforge.cli.api.ApiClient;
import com.gitforge.cli.output.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * Everything the server owns.
 *
 * <p>Repositories, releases, issues and analytics live in the server's database
 * behind its authorization rules, so the CLI reaches them over HTTP and never by
 * opening storage. That is not a stylistic choice: the engine's repository lock
 * is in-process, and a second process on the same volume could race a sweep
 * against a write.
 *
 * <p>Authorization is never decided here. Every one of these commands sends a
 * request and reports what came back — a 403 is the server's answer, not the
 * CLI's opinion, and a 404 for a private repository is passed through exactly as
 * received so the CLI cannot become the thing that reveals it exists.
 */
public final class ServerCommands {

    private ServerCommands() {
    }

    static void registerAll() {
        Registry.register(new AuthLogin());
        Registry.register(new AuthLogout());
        Registry.register(new AuthStatus());
        Registry.register(new AuthToken());

        Registry.register(new RepoList());
        Registry.register(new RepoShow());
        Registry.register(new RepoCreate());
        Registry.register(new RepoDelete());
        Registry.register(new RepoVisibility());
        Registry.register(new RepoRefs());

        Registry.register(new ReleaseList());
        Registry.register(new ReleaseShow());
        Registry.register(new ReleaseCreate());
        Registry.register(new ReleaseEdit());
        Registry.register(new ReleaseDelete());

        Registry.register(new IssueList());
        Registry.register(new IssueShow());
        Registry.register(new IssueCreate());
        Registry.register(new IssueEdit());
        Registry.register(new IssueClose());
        Registry.register(new IssueReopen());
        Registry.register(new IssueComment());

        for (String surface : List.of(
                "overview", "activity", "commits", "contributors", "refs", "storage", "health")) {
            Registry.register(new Insights(surface));
        }
    }

    // --------------------------------------------------------------- helpers

    /** The repository this command acts on, as {@code owner/name}. */
    static String[] repository(Context context) {
        String slug = context.options().repo();
        if (slug == null || slug.isBlank()) {
            throw CliException.usage(
                    "Which repository? Pass --repo <owner/name> or set GITFORGE_REPO.");
        }
        String[] parts = slug.split("/");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw CliException.usage("--repo must be owner/name, got: " + slug);
        }
        return parts;
    }

    static String repoPath(Context context) {
        String[] parts = repository(context);
        return "/repositories/" + ApiClient.segment(parts[0]) + "/" + ApiClient.segment(parts[1]);
    }

    static String flag(List<String> args, String name) {
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).equals(name)) {
                if (i + 1 >= args.size()) {
                    throw CliException.usage(name + " needs a value");
                }
                return args.get(i + 1);
            }
            if (args.get(i).startsWith(name + "=")) {
                return args.get(i).substring(name.length() + 1);
            }
        }
        return null;
    }

    static boolean has(List<String> args, String name) {
        return args.contains(name);
    }

    static List<String> positional(List<String> args) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.startsWith("--")) {
                if (!arg.contains("=") && takesValue(arg)) {
                    i++;
                }
                continue;
            }
            out.add(arg);
        }
        return out;
    }

    private static boolean takesValue(String flag) {
        return List.of("--title", "--body", "--tag", "--name", "--state", "--message",
                "--visibility", "--description", "--from", "--to", "--bucket", "--limit")
                .contains(flag);
    }

    // ------------------------------------------------------------------ auth

    /**
     * Exchanges credentials for a token.
     *
     * <p>The password is read from the environment or standard input, never from
     * {@code argv} — every other process on the machine can read another
     * process's arguments — and it is never written anywhere. Only the token the
     * server returns is stored.
     */
    public static final class AuthLogin implements Command {

        @Override
        public String name() {
            return "auth login";
        }

        @Override
        public String summary() {
            return "Exchange an email and password for a token";
        }

        @Override
        public String usage() {
            return "gitforge auth login --email <email>   (password from GITFORGE_PASSWORD or stdin)";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public Object run(Context context, List<String> args) {
            String email = flag(args, "--email");
            if (email == null) {
                throw CliException.usage(usage());
            }
            if (flag(args, "--password") != null) {
                throw CliException.usage(
                        "Refusing to take a password on the command line: other processes can read "
                                + "it. Set GITFORGE_PASSWORD or pipe it on stdin.");
            }

            String password = context.environment().get("GITFORGE_PASSWORD");
            if (password == null || password.isBlank()) {
                password = readSecret();
            }
            if (password == null || password.isBlank()) {
                throw CliException.usage("No password supplied");
            }

            ApiClient api = new ApiClient(context);
            JsonNode response = api.post("/auth/login", Map.of("email", email, "password", password));
            String token = response.path("token").asString();
            if (token == null || token.isBlank()) {
                throw CliException.failure("The server did not return a token");
            }

            if (context.options().dryRun()) {
                return Json.map("host", api.host(), "email", email, "stored", false, "mutated", false);
            }
            context.credentials().store(api.host(), token);
            context.decided("ALLOW");
            return Json.map(
                    "host", api.host(),
                    "email", email,
                    "user", ApiClient.plain(response.path("user")),
                    "expiresInSeconds", response.path("expiresInSeconds").asLong(0),
                    "stored", true,
                    "mutated", true);
        }

        private static String readSecret() {
            java.io.Console console = System.console();
            if (console != null) {
                char[] typed = console.readPassword("Password: ");
                return typed == null ? null : new String(typed);
            }
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8))) {
                return reader.readLine();
            } catch (java.io.IOException unreadable) {
                return null;
            }
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line("Signed in to " + data.get("host") + " as " + data.get("email"));
        }
    }

    public static final class AuthLogout implements Command {

        @Override
        public String name() {
            return "auth logout";
        }

        @Override
        public String summary() {
            return "Forget the stored token for a host";
        }

        @Override
        public String usage() {
            return "gitforge auth logout";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public Object run(Context context, List<String> args) {
            ApiClient api = new ApiClient(context);
            if (context.options().dryRun()) {
                return Json.map("host", api.host(), "removed", false, "mutated", false);
            }
            boolean removed = context.credentials().clear(api.host());
            context.redactor().forgetAll();
            return Json.map("host", api.host(), "removed", removed, "mutated", removed);
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line(Boolean.TRUE.equals(data.get("removed"))
                    ? "Signed out of " + data.get("host")
                    : "No token was stored for " + data.get("host"));
        }
    }

    /** Whether a token is held, and whether the server still accepts it. */
    public static final class AuthStatus implements Command {

        @Override
        public String name() {
            return "auth status";
        }

        @Override
        public String summary() {
            return "Show which hosts have a token, and whether it still works";
        }

        @Override
        public String usage() {
            return "gitforge auth status";
        }

        @Override
        public Object run(Context context, List<String> args) {
            Map<String, Object> data = new LinkedHashMap<>(context.credentials().describe());
            try {
                ApiClient api = new ApiClient(context);
                data.put("host", api.host());
                if (context.credentials().tokenFor(api.host()).isEmpty()) {
                    data.put("authenticated", false);
                    data.put("reason", "No token stored for this host");
                    return data;
                }
                JsonNode me = api.get("/auth/me");
                data.put("authenticated", true);
                data.put("user", ApiClient.plain(me));
            } catch (CliException failed) {
                // A rejected token is an answer to the question, not a failure of
                // the command: `auth status` is asking whether it works.
                data.put("authenticated", false);
                data.put("reason", failed.getMessage());
            }
            return data;
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line("Credentials file: " + data.get("file")
                    + " (" + data.get("permissions") + ")");
            context.out().line("Hosts with a token: " + data.get("hosts"));
            context.out().line("Authenticated: " + data.get("authenticated")
                    + (data.get("reason") == null ? "" : " — " + data.get("reason")));
        }
    }

    /**
     * Prints the stored token.
     *
     * <p>The one command that will, and only when asked by name and with
     * {@code --yes}. Piping a token into another tool is a real need; doing it by
     * accident is a real incident.
     */
    public static final class AuthToken implements Command {

        @Override
        public String name() {
            return "auth token";
        }

        @Override
        public String summary() {
            return "Print the stored token (requires --yes)";
        }

        @Override
        public String usage() {
            return "gitforge auth token --yes";
        }

        @Override
        public Object run(Context context, List<String> args) {
            ApiClient api = new ApiClient(context);
            String token = context.credentials().tokenFor(api.host())
                    .orElseThrow(() -> CliException.notFound(
                            "No token stored for " + api.host()));
            if (!context.options().assumeYes()) {
                throw CliException.refused("CONFIRMATION_REQUIRED",
                        "This prints a credential. Pass --yes if that is what you want.");
            }
            // Deliberately bypasses the redactor: this command's entire purpose
            // is to emit the token, and masking it here would make the command a
            // lie. Everywhere else, the redactor still applies.
            context.out().line(token);
            return Json.map("host", api.host(), "printed", true);
        }

        @Override
        public void describe(Context context, Object result) {
            // Already printed above; printing again would duplicate it.
        }
    }

    // ------------------------------------------------------------------ repo

    public static final class RepoList implements Command {

        @Override
        public String name() {
            return "repo list";
        }

        @Override
        public String summary() {
            return "List repositories visible to you";
        }

        @Override
        public String usage() {
            return "gitforge repo list [--user <username>]";
        }

        @Override
        public Object run(Context context, List<String> args) {
            ApiClient api = new ApiClient(context);
            String user = flag(args, "--user");
            JsonNode response = user == null
                    ? api.get("/repositories")
                    : api.get("/users/" + ApiClient.segment(user) + "/repositories");
            return ApiClient.plain(response);
        }

        @Override
        public void describe(Context context, Object result) {
            List<?> content = contentOf(result);
            for (Object row : content) {
                Map<?, ?> repo = (Map<?, ?>) row;
                context.out().line(String.format("  %-40s %s",
                        repo.get("ownerUsername") + "/" + repo.get("name"), repo.get("visibility")));
            }
        }
    }

    /** Paged responses carry their rows under {@code content}; plain lists do not. */
    static List<?> contentOf(Object result) {
        if (result instanceof Map<?, ?> map && map.get("content") instanceof List<?> content) {
            return content;
        }
        return result instanceof List<?> list ? list : List.of();
    }

    public static final class RepoShow implements Command {

        @Override
        public String name() {
            return "repo show";
        }

        @Override
        public String summary() {
            return "Show one repository";
        }

        @Override
        public String usage() {
            return "gitforge repo show --repo <owner/name>";
        }

        @Override
        public Object run(Context context, List<String> args) {
            return ApiClient.plain(new ApiClient(context).get(repoPath(context)));
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line(data.get("ownerUsername") + "/" + data.get("name")
                    + "  [" + data.get("visibility") + "]");
            if (data.get("description") != null) {
                context.out().line("  " + data.get("description"));
            }
        }
    }

    public static final class RepoCreate implements Command {

        @Override
        public String name() {
            return "repo create";
        }

        @Override
        public String summary() {
            return "Create a repository on the server";
        }

        @Override
        public String usage() {
            return "gitforge repo create <name> [--description <text>] [--visibility PUBLIC|PRIVATE]";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public Object run(Context context, List<String> args) {
            List<String> names = positional(args);
            if (names.size() != 1) {
                throw CliException.usage(usage());
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", names.get(0));
            body.put("description", flag(args, "--description"));
            body.put("visibility", java.util.Optional.ofNullable(flag(args, "--visibility"))
                    .orElse("PUBLIC").toUpperCase(java.util.Locale.ROOT));

            if (context.options().dryRun() || context.options().preview()) {
                return Json.map(
                        "operation", "repo create",
                        "wouldCreate", Json.map("objects", 0, "commits", 0),
                        "refsWouldMove", List.of(),
                        "refsWouldDelete", List.of(),
                        "databaseRecords", List.of(Json.map("table", "repos", "action", "INSERT",
                                "name", names.get(0))),
                        "authorization", Json.map("decision", "DEFERRED",
                                "reason", "The server decides; nothing was sent"),
                        "finalState", body,
                        "mutated", false);
            }
            JsonNode created = new ApiClient(context).post("/repositories", body);
            context.decided("ALLOW");
            return ApiClient.plain(created);
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            if (Boolean.FALSE.equals(data.get("mutated"))) {
                context.out().line("Would create a repository — nothing was sent.");
                return;
            }
            context.out().line("Created " + data.get("ownerUsername") + "/" + data.get("name"));
        }
    }

    public static final class RepoDelete implements Command {

        @Override
        public String name() {
            return "repo delete";
        }

        @Override
        public String summary() {
            return "Delete a repository on the server";
        }

        @Override
        public String usage() {
            return "gitforge repo delete --repo <owner/name> --yes";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public Object run(Context context, List<String> args) {
            ApiClient api = new ApiClient(context);
            JsonNode repo = api.get(repoPath(context));
            String id = repo.path("id").asString();

            if (context.options().dryRun() || context.options().preview()) {
                return Json.map(
                        "operation", "repo delete",
                        "wouldCreate", Json.map("objects", 0, "commits", 0),
                        "refsWouldMove", List.of(),
                        "refsWouldDelete", List.of(),
                        "databaseRecords", List.of(Json.map("table", "repos", "action", "DELETE", "id", id)),
                        "authorization", Json.map("decision", "DEFERRED", "reason", "The server decides"),
                        "note", "This removes the repository and its objects on the server.",
                        "mutated", false);
            }
            context.confirm("Delete " + context.options().repo() + " and everything in it?");
            api.delete("/repositories/" + ApiClient.segment(id));
            context.decided("ALLOW");
            return Json.map("repository", context.options().repo(), "deleted", true, "mutated", true);
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line(Boolean.FALSE.equals(data.get("mutated"))
                    ? "Would delete the repository — nothing was sent."
                    : "Deleted " + data.get("repository"));
        }
    }

    public static final class RepoVisibility implements Command {

        @Override
        public String name() {
            return "repo visibility";
        }

        @Override
        public String summary() {
            return "Show or change a repository's visibility";
        }

        @Override
        public String usage() {
            return "gitforge repo visibility [PUBLIC|PRIVATE] --repo <owner/name>";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public Object run(Context context, List<String> args) {
            ApiClient api = new ApiClient(context);
            JsonNode repo = api.get(repoPath(context));
            List<String> wanted = positional(args);

            if (wanted.isEmpty()) {
                // Reading is not a mutation, even though the command can mutate.
                return Json.map(
                        "repository", context.options().repo(),
                        "visibility", repo.path("visibility").asString(),
                        "mutated", false);
            }
            String target = wanted.get(0).toUpperCase(java.util.Locale.ROOT);
            if (!target.equals("PUBLIC") && !target.equals("PRIVATE")) {
                throw CliException.usage("Visibility must be PUBLIC or PRIVATE, got " + wanted.get(0));
            }
            String from = repo.path("visibility").asString();

            if (context.options().dryRun() || context.options().preview()) {
                return Json.map(
                        "operation", "repo visibility",
                        "wouldCreate", Json.map("objects", 0, "commits", 0),
                        "refsWouldMove", List.of(),
                        "refsWouldDelete", List.of(),
                        "databaseRecords", List.of(Json.map(
                                "table", "repos", "action", "UPDATE", "from", from, "to", target)),
                        "authorization", Json.map("decision", "DEFERRED", "reason", "The server decides"),
                        "mutated", false);
            }
            if (from.equals("PUBLIC") && target.equals("PRIVATE")) {
                context.confirm("Make " + context.options().repo() + " private?");
            }
            JsonNode updated = api.patch(
                    "/repositories/" + ApiClient.segment(repo.path("id").asString()),
                    Map.of("visibility", target));
            context.decided("ALLOW");
            return Json.map(
                    "repository", context.options().repo(),
                    "from", from,
                    "visibility", updated.path("visibility").asString(),
                    "mutated", true);
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line(data.get("repository") + ": " + data.get("visibility"));
        }
    }

    public static final class RepoRefs implements Command {

        @Override
        public String name() {
            return "repo refs";
        }

        @Override
        public String summary() {
            return "Show the branches and tags a server repository holds";
        }

        @Override
        public String usage() {
            return "gitforge repo refs --repo <owner/name>";
        }

        @Override
        public Object run(Context context, List<String> args) {
            ApiClient api = new ApiClient(context);
            String path = repoPath(context);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("repository", context.options().repo());
            data.put("branches", ApiClient.plain(api.get(path + "/branches")));
            data.put("tags", ApiClient.plain(api.get(path + "/tags")));
            data.put("head", ApiClient.plain(api.get(path + "/head")));
            return data;
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line("Branches:");
            for (Object row : contentOf(data.get("branches"))) {
                Map<?, ?> branch = (Map<?, ?>) row;
                context.out().line("  " + branch.get("name") + "  " + branch.get("commit"));
            }
            context.out().line("Tags:");
            for (Object row : contentOf(data.get("tags"))) {
                Map<?, ?> tag = (Map<?, ?>) row;
                context.out().line("  " + tag.get("name") + "  " + tag.get("commit"));
            }
        }
    }

    // -------------------------------------------------------------- releases

    public static final class ReleaseList implements Command {

        @Override
        public String name() {
            return "release list";
        }

        @Override
        public String summary() {
            return "List releases (drafts appear only for the owner)";
        }

        @Override
        public String usage() {
            return "gitforge release list --repo <owner/name>";
        }

        @Override
        public Object run(Context context, List<String> args) {
            return ApiClient.plain(new ApiClient(context).get(repoPath(context) + "/releases"));
        }

        @Override
        public void describe(Context context, Object result) {
            for (Object row : contentOf(result)) {
                Map<?, ?> release = (Map<?, ?>) row;
                List<String> marks = new ArrayList<>();
                if (Boolean.TRUE.equals(release.get("draft"))) {
                    marks.add("draft");
                }
                if (Boolean.TRUE.equals(release.get("prerelease"))) {
                    marks.add("prerelease");
                }
                context.out().line(String.format("  %-20s %-28s %s",
                        release.get("tag"), release.get("name"), String.join(", ", marks)));
            }
        }
    }

    public static final class ReleaseShow implements Command {

        @Override
        public String name() {
            return "release show";
        }

        @Override
        public String summary() {
            return "Show one release";
        }

        @Override
        public String usage() {
            return "gitforge release show <id> --repo <owner/name>";
        }

        @Override
        public Object run(Context context, List<String> args) {
            List<String> ids = positional(args);
            if (ids.size() != 1) {
                throw CliException.usage(usage());
            }
            return ApiClient.plain(new ApiClient(context)
                    .get(repoPath(context) + "/releases/" + ApiClient.segment(ids.get(0))));
        }
    }

    public static final class ReleaseCreate implements Command {

        @Override
        public String name() {
            return "release create";
        }

        @Override
        public String summary() {
            return "Publish a release for an existing tag";
        }

        @Override
        public String usage() {
            return "gitforge release create --tag <tag> --name <name> [--body <text>] "
                    + "[--draft] [--prerelease] --repo <owner/name>";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public Object run(Context context, List<String> args) {
            String tag = flag(args, "--tag");
            String name = flag(args, "--name");
            if (tag == null || name == null) {
                throw CliException.usage(usage());
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tag", tag);
            body.put("name", name);
            body.put("body", flag(args, "--body"));
            body.put("draft", has(args, "--draft"));
            body.put("prerelease", has(args, "--prerelease"));

            if (context.options().dryRun() || context.options().preview()) {
                return Json.map(
                        "operation", "release create",
                        "wouldCreate", Json.map("objects", 0, "commits", 0),
                        "refsWouldMove", List.of(),
                        "refsWouldDelete", List.of(),
                        "databaseRecords", List.of(Json.map("table", "releases", "action", "INSERT",
                                "tag", tag)),
                        "authorization", Json.map("decision", "DEFERRED", "reason", "The server decides"),
                        "finalState", body,
                        "mutated", false);
            }
            JsonNode created = new ApiClient(context).post(repoPath(context) + "/releases", body);
            context.decided("ALLOW");
            return ApiClient.plain(created);
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line(Boolean.FALSE.equals(data.get("mutated"))
                    ? "Would create a release — nothing was sent."
                    : "Created release " + data.get("name") + " for tag " + data.get("tag"));
        }
    }

    public static final class ReleaseEdit implements Command {

        @Override
        public String name() {
            return "release edit";
        }

        @Override
        public String summary() {
            return "Change a release's name, body or state";
        }

        @Override
        public String usage() {
            return "gitforge release edit <id> [--name <name>] [--body <text>] "
                    + "[--draft] [--prerelease] --repo <owner/name>";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public Object run(Context context, List<String> args) {
            List<String> ids = positional(args);
            if (ids.size() != 1) {
                throw CliException.usage(usage());
            }
            Map<String, Object> body = new LinkedHashMap<>();
            if (flag(args, "--name") != null) {
                body.put("name", flag(args, "--name"));
            }
            if (flag(args, "--body") != null) {
                body.put("body", flag(args, "--body"));
            }
            if (has(args, "--draft")) {
                body.put("draft", true);
            }
            if (has(args, "--prerelease")) {
                body.put("prerelease", true);
            }
            if (body.isEmpty()) {
                throw CliException.usage("Nothing to change. " + usage());
            }

            if (context.options().dryRun() || context.options().preview()) {
                return Json.map(
                        "operation", "release edit",
                        "databaseRecords", List.of(Json.map("table", "releases", "action", "UPDATE",
                                "id", ids.get(0), "fields", body)),
                        "refsWouldMove", List.of(),
                        "refsWouldDelete", List.of(),
                        "authorization", Json.map("decision", "DEFERRED", "reason", "The server decides"),
                        "mutated", false);
            }
            JsonNode updated = new ApiClient(context)
                    .patch(repoPath(context) + "/releases/" + ApiClient.segment(ids.get(0)), body);
            context.decided("ALLOW");
            return ApiClient.plain(updated);
        }
    }

    public static final class ReleaseDelete implements Command {

        @Override
        public String name() {
            return "release delete";
        }

        @Override
        public String summary() {
            return "Delete a release (the tag and its commit remain)";
        }

        @Override
        public String usage() {
            return "gitforge release delete <id> --yes --repo <owner/name>";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public Object run(Context context, List<String> args) {
            List<String> ids = positional(args);
            if (ids.size() != 1) {
                throw CliException.usage(usage());
            }
            if (context.options().dryRun() || context.options().preview()) {
                return Json.map(
                        "operation", "release delete",
                        "databaseRecords", List.of(Json.map("table", "releases", "action", "DELETE",
                                "id", ids.get(0))),
                        "refsWouldMove", List.of(),
                        "refsWouldDelete", List.of(),
                        "authorization", Json.map("decision", "DEFERRED", "reason", "The server decides"),
                        "note", "Deleting a release leaves its tag and the commit untouched.",
                        "mutated", false);
            }
            context.confirm("Delete release " + ids.get(0) + "?");
            new ApiClient(context).delete(repoPath(context) + "/releases/" + ApiClient.segment(ids.get(0)));
            context.decided("ALLOW");
            return Json.map("id", ids.get(0), "deleted", true, "mutated", true);
        }
    }

    // ---------------------------------------------------------------- issues

    public static final class IssueList implements Command {

        @Override
        public String name() {
            return "issue list";
        }

        @Override
        public String summary() {
            return "List issues";
        }

        @Override
        public String usage() {
            return "gitforge issue list [--state OPEN|CLOSED] --repo <owner/name>";
        }

        @Override
        public Object run(Context context, List<String> args) {
            String state = flag(args, "--state");
            String query = state == null ? "" : "?status=" + ApiClient.segment(
                    state.toUpperCase(java.util.Locale.ROOT));
            return ApiClient.plain(new ApiClient(context).get(repoPath(context) + "/issues" + query));
        }

        @Override
        public void describe(Context context, Object result) {
            for (Object row : contentOf(result)) {
                Map<?, ?> issue = (Map<?, ?>) row;
                context.out().line(String.format("  #%-5s %-10s %s",
                        issue.get("number"), issue.get("status"), issue.get("title")));
            }
        }
    }

    public static final class IssueShow implements Command {

        @Override
        public String name() {
            return "issue show";
        }

        @Override
        public String summary() {
            return "Show one issue";
        }

        @Override
        public String usage() {
            return "gitforge issue show <number> --repo <owner/name>";
        }

        @Override
        public Object run(Context context, List<String> args) {
            List<String> numbers = positional(args);
            if (numbers.size() != 1) {
                throw CliException.usage(usage());
            }
            return ApiClient.plain(new ApiClient(context)
                    .get(repoPath(context) + "/issues/" + ApiClient.segment(numbers.get(0))));
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line("#" + data.get("number") + "  " + data.get("title")
                    + "  [" + data.get("status") + "]");
            if (data.get("body") != null) {
                context.out().line("");
                context.out().line(String.valueOf(data.get("body")));
            }
        }
    }

    public static final class IssueCreate implements Command {

        @Override
        public String name() {
            return "issue create";
        }

        @Override
        public String summary() {
            return "Open an issue";
        }

        @Override
        public String usage() {
            return "gitforge issue create --title <title> [--body <text>] --repo <owner/name>";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public Object run(Context context, List<String> args) {
            String title = flag(args, "--title");
            if (title == null) {
                throw CliException.usage(usage());
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("title", title);
            body.put("body", flag(args, "--body"));

            if (context.options().dryRun() || context.options().preview()) {
                return Json.map(
                        "operation", "issue create",
                        "databaseRecords", List.of(Json.map("table", "issues", "action", "INSERT",
                                "title", title)),
                        "refsWouldMove", List.of(),
                        "refsWouldDelete", List.of(),
                        "authorization", Json.map("decision", "DEFERRED", "reason", "The server decides"),
                        "mutated", false);
            }
            JsonNode created = new ApiClient(context).post(repoPath(context) + "/issues", body);
            context.decided("ALLOW");
            return ApiClient.plain(created);
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line(Boolean.FALSE.equals(data.get("mutated"))
                    ? "Would open an issue — nothing was sent."
                    : "Opened #" + data.get("number") + ": " + data.get("title"));
        }
    }

    public static final class IssueEdit implements Command {

        @Override
        public String name() {
            return "issue edit";
        }

        @Override
        public String summary() {
            return "Change an issue's title or body";
        }

        @Override
        public String usage() {
            return "gitforge issue edit <number> [--title <t>] [--body <b>] --repo <owner/name>";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public Object run(Context context, List<String> args) {
            return IssueState.patch(context, args, usage(), null);
        }
    }

    public static final class IssueClose implements Command {

        @Override
        public String name() {
            return "issue close";
        }

        @Override
        public String summary() {
            return "Close an issue";
        }

        @Override
        public String usage() {
            return "gitforge issue close <number> --repo <owner/name>";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public Object run(Context context, List<String> args) {
            return IssueState.patch(context, args, usage(), "CLOSED");
        }
    }

    public static final class IssueReopen implements Command {

        @Override
        public String name() {
            return "issue reopen";
        }

        @Override
        public String summary() {
            return "Reopen a closed issue";
        }

        @Override
        public String usage() {
            return "gitforge issue reopen <number> --repo <owner/name>";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public Object run(Context context, List<String> args) {
            return IssueState.patch(context, args, usage(), "OPEN");
        }
    }

    /**
     * The shared path for changing an issue.
     *
     * <p>Editing, closing and reopening are the same request with different
     * fields, so they share one implementation rather than three that drift.
     */
    static final class IssueState {

        private IssueState() {
        }

        static Object patch(Context context, List<String> args, String usage, String status) {
            List<String> numbers = positional(args);
            if (numbers.size() != 1) {
                throw CliException.usage(usage);
            }
            ApiClient api = new ApiClient(context);
            JsonNode issue = api.get(repoPath(context) + "/issues/" + ApiClient.segment(numbers.get(0)));
            String id = issue.path("id").asString();

            Map<String, Object> body = new LinkedHashMap<>();
            if (status != null) {
                body.put("status", status);
            }
            if (flag(args, "--title") != null) {
                body.put("title", flag(args, "--title"));
            }
            if (flag(args, "--body") != null) {
                body.put("body", flag(args, "--body"));
            }
            if (body.isEmpty()) {
                throw CliException.usage("Nothing to change. " + usage);
            }

            if (context.options().dryRun() || context.options().preview()) {
                return Json.map(
                        "operation", "issue update",
                        "databaseRecords", List.of(Json.map("table", "issues", "action", "UPDATE",
                                "id", id, "fields", body)),
                        "refsWouldMove", List.of(),
                        "refsWouldDelete", List.of(),
                        "authorization", Json.map("decision", "DEFERRED", "reason", "The server decides"),
                        "mutated", false);
            }
            JsonNode updated = api.patch("/issues/" + ApiClient.segment(id), body);
            context.decided("ALLOW");
            return ApiClient.plain(updated);
        }
    }

    public static final class IssueComment implements Command {

        @Override
        public String name() {
            return "issue comment";
        }

        @Override
        public String summary() {
            return "Comment on an issue";
        }

        @Override
        public String usage() {
            return "gitforge issue comment <number> --body <text> --repo <owner/name>";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public Object run(Context context, List<String> args) {
            List<String> numbers = positional(args);
            String text = flag(args, "--body");
            if (numbers.size() != 1 || text == null) {
                throw CliException.usage(usage());
            }
            if (context.options().dryRun() || context.options().preview()) {
                return Json.map(
                        "operation", "issue comment",
                        "databaseRecords", List.of(Json.map("table", "issue_comments",
                                "action", "INSERT", "issue", numbers.get(0))),
                        "refsWouldMove", List.of(),
                        "refsWouldDelete", List.of(),
                        "authorization", Json.map("decision", "DEFERRED", "reason", "The server decides"),
                        "mutated", false);
            }
            JsonNode created = new ApiClient(context).post(
                    repoPath(context) + "/issues/" + ApiClient.segment(numbers.get(0)) + "/comments",
                    Map.of("body", text));
            context.decided("ALLOW");
            return ApiClient.plain(created);
        }
    }

    // -------------------------------------------------------------- insights

    /**
     * The analytics surfaces, one command each.
     *
     * <p>Nothing is computed here. Every figure comes from the server, which
     * recomputes it from the object store on each request — so a number the CLI
     * prints is a number the engine derived, and the CLI has no opportunity to
     * invent one.
     *
     * <p>{@code health} is the one with a cost. Its scan re-hashes every object
     * and holds the repository's exclusive lock, so it is requested only when
     * {@code --scan} is passed, and the unscanned answer says {@code NOT_VERIFIED}
     * rather than implying health it did not check.
     */
    public static final class Insights implements Command {

        private final String surface;

        Insights(String surface) {
            this.surface = surface;
        }

        @Override
        public String name() {
            return "insights " + surface;
        }

        @Override
        public String summary() {
            return switch (surface) {
                case "overview" -> "Commits, contributors, branches and stored objects";
                case "activity" -> "What happened in a window of time";
                case "commits" -> "The shape of the commit graph";
                case "contributors" -> "Who authored the commits";
                case "refs" -> "Branches, tags and remote-tracking references";
                case "storage" -> "Object counts and bytes by type";
                case "health" -> "Reachability and integrity (scan is opt-in)";
                default -> surface;
            };
        }

        @Override
        public String usage() {
            return "gitforge insights " + surface + " --repo <owner/name>"
                    + (surface.equals("health") ? " [--scan]" : "")
                    + (rangeable() ? " [--from <date>] [--to <date>]" : "")
                    + (surface.equals("activity") ? " [--bucket day|week]" : "");
        }

        private boolean rangeable() {
            return List.of("activity", "contributors").contains(surface);
        }

        @Override
        public Object run(Context context, List<String> args) {
            String path = repoPath(context) + "/insights";
            StringBuilder query = new StringBuilder();
            if (!surface.equals("overview")) {
                path = path + "/" + surface;
            }
            if (rangeable()) {
                append(query, "from", flag(args, "--from"));
                append(query, "to", flag(args, "--to"));
            }
            if (surface.equals("activity")) {
                append(query, "bucket", flag(args, "--bucket"));
            }
            if (surface.equals("health") && has(args, "--scan")) {
                append(query, "scan", "true");
                context.out().trace("requesting a full scan: this holds the repository lock");
            }
            return ApiClient.plain(new ApiClient(context).get(path + query));
        }

        private static void append(StringBuilder query, String key, String value) {
            if (value == null || value.isBlank()) {
                return;
            }
            query.append(query.isEmpty() ? '?' : '&')
                    .append(key).append('=').append(ApiClient.segment(value));
        }

        @Override
        public void describe(Context context, Object result) {
            if (result instanceof Map<?, ?> data) {
                data.forEach((key, value) -> {
                    if (!(value instanceof List) && !(value instanceof Map)) {
                        context.out().line(String.format("  %-24s %s", key, value));
                    }
                });
                return;
            }
            context.out().line(Json.pretty(result));
        }
    }
}
