package com.gitforge.cli.command;

import com.gitforge.cli.CliException;
import com.gitforge.cli.Context;
import com.gitforge.cli.api.ApiClient;
import com.gitforge.cli.api.CliRemoteTransport;
import com.gitforge.cli.local.Workspace;
import com.gitforge.cli.output.Json;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.remote.FetchService;
import com.gitforge.vcs.remote.PushService;
import com.gitforge.vcs.remote.Remote;
import com.gitforge.vcs.remote.RemoteStore;
import com.gitforge.vcs.remote.RemoteTransport;
import com.gitforge.vcs.remote.RemoteUrl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Moving objects between a local repository and a server.
 *
 * <p>The transfer logic is the engine's — {@link FetchService} and
 * {@link PushService} decide what to ask for and what to verify before anything
 * moves, and they are the same classes the server uses when it talks to another
 * instance. These commands supply a transport and a repository and then get out
 * of the way.
 *
 * <p>There is no force push. A non-fast-forward push is refused by the engine
 * because accepting one silently discards commits the other side has, and
 * offering a flag to do it anyway would make the refusal advisory. When a push
 * is refused, {@code gitforge explain push} says exactly which commits are in
 * the way.
 *
 * <p>Remote URLs go through {@link RemoteUrl}, which allows only http and https,
 * refuses embedded credentials, and requires every address the name resolves to
 * be public. That guard is not new here; it is the same one the server applies,
 * and reusing it is the point.
 */
public final class RemoteCommands {

    private RemoteCommands() {
    }

    static void registerAll() {
        Registry.register(new RemoteList());
        Registry.register(new RemoteAdd());
        Registry.register(new RemoteRemove());
        Registry.register(new RemoteShow());
        Registry.register(new RemoteFetch());
        Registry.register(new RemotePush());
        Registry.register(new RemotePull());
        Registry.register(new RemoteDiagnose());
        Registry.register(new Clone());
    }

    /** Where a local repository records its remotes. */
    private static RemoteStore storeFor(Workspace workspace) {
        return new RemoteStore(workspace.repository().objects() == null
                ? workspace.treeRoot()
                : repositoryRootOf(workspace));
    }

    /**
     * The repository directory, which is where remotes are stored.
     *
     * <p>Derived from the working tree rather than passed around, so there is one
     * definition of the layout and it lives in {@link Workspace}.
     */
    private static java.nio.file.Path repositoryRootOf(Workspace workspace) {
        return workspace.treeRoot().resolve(Workspace.METADATA).resolve("repository");
    }

    private static Remote require(Workspace workspace, String name) {
        return storeFor(workspace).list().stream()
                .filter(remote -> remote.name().equals(name))
                .findFirst()
                .orElseThrow(() -> CliException.notFound("No such remote: " + name));
    }

    // ------------------------------------------------------------------ list

    public static final class RemoteList implements Command {

        @Override
        public String name() {
            return "remote list";
        }

        @Override
        public String summary() {
            return "List configured remotes";
        }

        @Override
        public String usage() {
            return "gitforge remote list";
        }

        @Override
        public Object run(Context context, List<String> args) {
            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Remote remote : storeFor(workspace).list()) {
                rows.add(Json.map("name", remote.name(), "url", remote.url()));
            }
            return Json.map("count", rows.size(), "remotes", rows);
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            for (Object row : (List<?>) data.get("remotes")) {
                Map<?, ?> remote = (Map<?, ?>) row;
                context.out().line(String.format("  %-16s %s", remote.get("name"), remote.get("url")));
            }
        }
    }

    // ------------------------------------------------------------------- add

    public static final class RemoteAdd implements Command {

        @Override
        public String name() {
            return "remote add";
        }

        @Override
        public String summary() {
            return "Register a remote repository";
        }

        @Override
        public String usage() {
            return "gitforge remote add <name> <url> [--allow-private-addresses]";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public Object run(Context context, List<String> args) {
            List<String> words = args.stream().filter(arg -> !arg.startsWith("--")).toList();
            if (words.size() != 2) {
                throw CliException.usage(usage());
            }
            String name = words.get(0);
            String url = words.get(1);

            // The same validation the server applies: scheme allowlist, no
            // embedded credentials, and every resolved address must be public
            // unless the caller explicitly opts out for a local server.
            boolean allowPrivate = args.contains("--allow-private-addresses");
            String validated;
            try {
                validated = RemoteUrl.validate(url, allowPrivate);
            } catch (RuntimeException refused) {
                throw CliException.usage(String.valueOf(refused.getMessage()));
            }

            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            RemoteStore store = storeFor(workspace);
            if (store.list().stream().anyMatch(remote -> remote.name().equals(name))) {
                throw CliException.conflict("A remote called " + name + " already exists");
            }

            if (context.options().dryRun() || context.options().preview()) {
                return Json.map(
                        "operation", "remote add",
                        "wouldCreate", Json.map("objects", 0, "commits", 0),
                        "refsWouldMove", List.of(),
                        "refsWouldDelete", List.of(),
                        "databaseRecords", List.of(),
                        "authorization", Json.map("decision", "ALLOW", "reason", "local configuration"),
                        "finalState", Json.map("name", name, "url", validated),
                        "mutated", false);
            }
            store.save(new Remote(name, validated));
            return Json.map("name", name, "url", validated, "mutated", true);
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line(Boolean.FALSE.equals(data.get("mutated"))
                    ? "Would add the remote — nothing was written."
                    : "Added remote " + data.get("name"));
        }
    }

    // ---------------------------------------------------------------- remove

    public static final class RemoteRemove implements Command {

        @Override
        public String name() {
            return "remote remove";
        }

        @Override
        public String summary() {
            return "Forget a remote";
        }

        @Override
        public String usage() {
            return "gitforge remote remove <name>";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public Object run(Context context, List<String> args) {
            List<String> words = args.stream().filter(arg -> !arg.startsWith("--")).toList();
            if (words.size() != 1) {
                throw CliException.usage(usage());
            }
            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            Remote remote = require(workspace, words.get(0));

            if (context.options().dryRun() || context.options().preview()) {
                return Json.map(
                        "operation", "remote remove",
                        "refsWouldMove", List.of(),
                        "refsWouldDelete", List.of(),
                        "databaseRecords", List.of(),
                        "authorization", Json.map("decision", "ALLOW", "reason", "local configuration"),
                        "note", "Removing a remote forgets a URL. Objects already fetched remain.",
                        "mutated", false);
            }
            boolean removed = storeFor(workspace).delete(remote.name());
            return Json.map("name", remote.name(), "removed", removed, "mutated", removed);
        }
    }

    // ------------------------------------------------------------------ show

    public static final class RemoteShow implements Command {

        @Override
        public String name() {
            return "remote show";
        }

        @Override
        public String summary() {
            return "Show a remote and the branches it advertises";
        }

        @Override
        public String usage() {
            return "gitforge remote show <name>";
        }

        @Override
        public Object run(Context context, List<String> args) {
            List<String> words = args.stream().filter(arg -> !arg.startsWith("--")).toList();
            if (words.size() != 1) {
                throw CliException.usage(usage());
            }
            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            Remote remote = require(workspace, words.get(0));

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("name", remote.name());
            data.put("url", remote.url());
            try {
                List<RemoteTransport.RemoteBranch> advertised =
                        new CliRemoteTransport(context).advertise(remote);
                List<Map<String, Object>> rows = new ArrayList<>();
                for (RemoteTransport.RemoteBranch branch : advertised) {
                    rows.add(Json.map("branch", branch.branch(), "commit", branch.commit()));
                }
                data.put("reachable", true);
                data.put("branches", rows);
            } catch (RuntimeException unreachable) {
                // Being unable to reach a remote is a fact about the remote, and
                // the command's job is to report facts about the remote.
                data.put("reachable", false);
                data.put("reason", String.valueOf(unreachable.getMessage()));
                data.put("branches", List.of());
            }
            return data;
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line(data.get("name") + "  " + data.get("url"));
            if (!Boolean.TRUE.equals(data.get("reachable"))) {
                context.out().line("  unreachable: " + data.get("reason"));
                return;
            }
            for (Object row : (List<?>) data.get("branches")) {
                Map<?, ?> branch = (Map<?, ?>) row;
                context.out().line("  " + branch.get("branch") + "  " + branch.get("commit"));
            }
        }
    }

    // ----------------------------------------------------------------- fetch

    public static final class RemoteFetch implements Command {

        @Override
        public String name() {
            return "remote fetch";
        }

        @Override
        public String summary() {
            return "Bring objects and remote-tracking refs up to date";
        }

        @Override
        public String usage() {
            return "gitforge remote fetch <name>";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public Object run(Context context, List<String> args) {
            List<String> words = args.stream().filter(arg -> !arg.startsWith("--")).toList();
            if (words.size() != 1) {
                throw CliException.usage(usage());
            }
            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            Remote remote = require(workspace, words.get(0));

            if (context.options().dryRun() || context.options().preview()) {
                return previewFetch(context, remote);
            }

            var repository = workspace.repository();
            FetchService fetch = new FetchService(
                    repository.objects(), repository.refs(),
                    new CliRemoteTransport(context), repository.lock());
            FetchService.Result result;
            try {
                result = fetch.fetch(remote);
            } catch (RuntimeException failed) {
                throw CliRemoteTransport.translate(failed);
            }
            result.updatedRefs().forEach(context::movedRef);
            return Json.map(
                    "remote", remote.name(),
                    "updatedRefs", result.updatedRefs(),
                    "receivedObjects", result.receivedObjects(),
                    "skippedBranches", result.skippedBranches(),
                    "mutated", !result.updatedRefs().isEmpty() || result.receivedObjects() > 0);
        }

        /** What a fetch would bring, established by asking rather than guessing. */
        private static Map<String, Object> previewFetch(Context context, Remote remote) {
            List<Map<String, Object>> would = new ArrayList<>();
            try {
                for (RemoteTransport.RemoteBranch branch : new CliRemoteTransport(context).advertise(remote)) {
                    would.add(Json.map(
                            "ref", "refs/remotes/" + remote.name() + "/" + branch.branch(),
                            "to", branch.commit()));
                }
            } catch (RuntimeException unreachable) {
                throw CliRemoteTransport.translate(unreachable);
            }
            return Json.map(
                    "operation", "remote fetch",
                    "remote", remote.name(),
                    "refsWouldMove", would,
                    "refsWouldDelete", List.of(),
                    "databaseRecords", List.of(),
                    "authorization", Json.map("decision", "DEFERRED", "reason", "The remote decides"),
                    "mutated", false);
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            if (Boolean.FALSE.equals(data.get("mutated")) && data.containsKey("operation")) {
                context.out().line("Would update " + ((List<?>) data.get("refsWouldMove")).size()
                        + " remote-tracking ref(s) — nothing was written.");
                return;
            }
            context.out().line("Fetched " + data.get("receivedObjects") + " object(s); "
                    + ((List<?>) data.get("updatedRefs")).size() + " ref(s) updated");
        }
    }

    // ------------------------------------------------------------------ push

    public static final class RemotePush implements Command {

        @Override
        public String name() {
            return "remote push";
        }

        @Override
        public String summary() {
            return "Send a branch to a remote (fast-forward only)";
        }

        @Override
        public String usage() {
            return "gitforge remote push <remote> [branch]";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public Object run(Context context, List<String> args) {
            List<String> words = args.stream().filter(arg -> !arg.startsWith("--")).toList();
            if (words.isEmpty() || words.size() > 2) {
                throw CliException.usage(usage());
            }
            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            Remote remote = require(workspace, words.get(0));
            String branch = words.size() == 2 ? words.get(1)
                    : workspace.repository().branches().currentBranch()
                            .orElseThrow(() -> CliException.conflict(
                                    "HEAD is detached; name the branch to push"));

            Optional<ObjectId> tip = workspace.repository().branches().getBranch(branch);
            if (tip.isEmpty()) {
                throw CliException.notFound("No such branch: " + branch);
            }

            if (context.options().dryRun() || context.options().preview()) {
                return Json.map(
                        "operation", "remote push",
                        "remote", remote.name(),
                        "branch", branch,
                        "refsWouldMove", List.of(Json.map(
                                "ref", "refs/heads/" + branch + " on " + remote.name(),
                                "to", tip.get().toHex(),
                                "fastForward", true)),
                        "refsWouldDelete", List.of(),
                        "databaseRecords", List.of(),
                        "authorization", Json.map("decision", "DEFERRED",
                                "reason", "The remote decides, and refuses a non-fast-forward"),
                        "note", "Run 'gitforge explain push " + branch
                                + "' to see whether this would fast-forward.",
                        "mutated", false);
            }

            var api = new ApiClient(context);
            String token = context.credentials().tokenFor(api.host())
                    .orElseThrow(() -> CliException.forbidden(
                            "Pushing needs a token. Run 'gitforge auth login' first."));

            var repository = workspace.repository();
            PushService push = new PushService(
                    repository.objects(), repository.refs(),
                    new CliRemoteTransport(context), repository.lock());
            PushService.Result result;
            try {
                result = push.push(remote, branch, token);
            } catch (RuntimeException failed) {
                throw CliRemoteTransport.translate(failed);
            }
            context.decided("ALLOW");
            return Json.map(
                    "remote", remote.name(),
                    "branch", result.branch(),
                    "commit", result.commit().toHex(),
                    "sentObjects", result.sentObjects(),
                    "storedObjects", result.storedObjects(),
                    "mutated", true);
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            if (Boolean.FALSE.equals(data.get("mutated"))) {
                context.out().line("Would push " + data.get("branch") + " to " + data.get("remote")
                        + " — nothing was sent.");
                return;
            }
            context.out().line("Pushed " + data.get("branch") + " to " + data.get("remote")
                    + "; " + data.get("storedObjects") + " object(s) stored");
        }
    }

    // ------------------------------------------------------------------ pull

    /**
     * Fetch, then fast-forward.
     *
     * <p>Deliberately not "fetch and merge". A pull that can create a merge
     * commit is a pull that can create one by accident, and the resulting history
     * is the most common thing people ask how to undo. This brings the objects
     * and moves the branch only when doing so loses nothing; anything else is a
     * merge, and a merge should be asked for.
     */
    public static final class RemotePull implements Command {

        @Override
        public String name() {
            return "remote pull";
        }

        @Override
        public String summary() {
            return "Fetch, then fast-forward the current branch if it is safe";
        }

        @Override
        public String usage() {
            return "gitforge remote pull <remote> [branch]";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public Object run(Context context, List<String> args) {
            List<String> words = args.stream().filter(arg -> !arg.startsWith("--")).toList();
            if (words.isEmpty() || words.size() > 2) {
                throw CliException.usage(usage());
            }
            // Fetch takes only a remote. Passing the branch through as well made
            // it a usage error, which then read as though pull itself was wrong.
            Object fetched = new RemoteFetch().run(context, List.of(words.get(0)));
            if (context.options().dryRun() || context.options().preview()) {
                Map<String, Object> data = new LinkedHashMap<>();
                if (fetched instanceof Map<?, ?> source) {
                    source.forEach((key, value) -> data.put(String.valueOf(key), value));
                }
                data.put("operation", "remote pull");
                data.put("note", "A pull fast-forwards only. It never creates a merge commit.");
                return data;
            }

            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            Remote remote = require(workspace, words.get(0));
            String branch = words.size() == 2 ? words.get(1)
                    : workspace.repository().branches().currentBranch()
                            .orElseThrow(() -> CliException.conflict("HEAD is detached"));

            String trackingRef = remote.name() + "/" + branch;
            Optional<ObjectId> remoteTip = workspace.repository().refs().listRemoteRefs().stream()
                    .filter(ref -> ref.remote().equals(remote.name()) && ref.branch().equals(branch))
                    .map(ref -> ref.commit())
                    .findFirst();
            if (remoteTip.isEmpty()) {
                throw CliException.notFound("The remote does not advertise " + branch);
            }

            Optional<ObjectId> local = workspace.repository().branches().getBranch(branch);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("remote", remote.name());
            data.put("branch", branch);
            data.put("fetched", fetched);

            if (local.isPresent() && local.get().equals(remoteTip.get())) {
                data.put("outcome", "ALREADY_UP_TO_DATE");
                data.put("mutated", false);
                return data;
            }
            // Only move the branch when the remote's tip already contains ours.
            var graph = new com.gitforge.vcs.graph.CommitGraph(workspace.repository().objects());
            boolean fastForward = local.isEmpty()
                    || graph.ancestorsOf(remoteTip.get()).contains(local.get());
            if (!fastForward) {
                throw CliException.conflict(
                        "Cannot fast-forward " + branch + ": it has commits " + trackingRef
                                + " does not. Merge " + trackingRef + " instead.");
            }
            workspace.repository().branches().updateBranch(branch, remoteTip.get());
            context.movedRef("refs/heads/" + branch);
            data.put("outcome", "FAST_FORWARDED");
            data.put("commit", remoteTip.get().toHex());
            data.put("mutated", true);
            return data;
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            Object outcome = data.get("outcome");
            context.out().line("Pull: " + (outcome == null ? "fetched" : outcome));
        }
    }

    // -------------------------------------------------------------- diagnose

    /**
     * Why a remote is or is not usable, in one place.
     *
     * <p>Remote problems are usually one of four things — the URL is refused, the
     * host is unreachable, there is no token, or the branch has diverged — and
     * each has a different fix. Reporting all four together turns a guessing game
     * into a checklist.
     */
    public static final class RemoteDiagnose implements Command {

        @Override
        public String name() {
            return "remote diagnose";
        }

        @Override
        public String summary() {
            return "Check a remote's URL, reachability, credentials and divergence";
        }

        @Override
        public String usage() {
            return "gitforge remote diagnose <name>";
        }

        @Override
        public Object run(Context context, List<String> args) {
            List<String> words = args.stream().filter(arg -> !arg.startsWith("--")).toList();
            if (words.size() != 1) {
                throw CliException.usage(usage());
            }
            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            Remote remote = require(workspace, words.get(0));

            List<Map<String, Object>> checks = new ArrayList<>();

            checks.add(check("url", () -> {
                RemoteUrl.validate(remote.url(), true);
                return "The URL is a form the engine accepts";
            }));

            List<RemoteTransport.RemoteBranch> advertised = new ArrayList<>();
            checks.add(check("reachable", () -> {
                advertised.addAll(new CliRemoteTransport(context).advertise(remote));
                return "The remote answered and advertises " + advertised.size() + " branch(es)";
            }));

            checks.add(check("credentials", () -> {
                String host = new ApiClient(context).host();
                if (context.credentials().tokenFor(host).isEmpty()) {
                    throw CliException.forbidden(
                            "No token stored for " + host + "; fetching may work, pushing will not");
                }
                return "A token is stored for " + host;
            }));

            checks.add(check("divergence", () -> {
                Optional<String> branch = workspace.repository().branches().currentBranch();
                if (branch.isEmpty()) {
                    return "HEAD is detached; nothing to compare";
                }
                Optional<RemoteTransport.RemoteBranch> theirs = advertised.stream()
                        .filter(row -> row.branch().equals(branch.get())).findFirst();
                if (theirs.isEmpty()) {
                    return "The remote does not have " + branch.get() + " yet; a push would create it";
                }
                Optional<ObjectId> ours = workspace.repository().branches().getBranch(branch.get());
                return ours.map(ObjectId::toHex).orElse("(none)").equals(theirs.get().commit())
                        ? branch.get() + " matches the remote"
                        : branch.get() + " differs from the remote; run 'gitforge explain push "
                                + branch.get() + "'";
            }));

            boolean allPassed = checks.stream().allMatch(row -> Boolean.TRUE.equals(row.get("ok")));
            return Json.map(
                    "remote", remote.name(),
                    "url", remote.url(),
                    "healthy", allPassed,
                    "checks", checks);
        }

        private static Map<String, Object> check(String name, java.util.concurrent.Callable<String> probe) {
            try {
                return Json.map("check", name, "ok", true, "detail", probe.call());
            } catch (Exception failed) {
                return Json.map("check", name, "ok", false,
                        "detail", String.valueOf(failed.getMessage()));
            }
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line(data.get("remote") + "  " + data.get("url"));
            for (Object row : (List<?>) data.get("checks")) {
                Map<?, ?> check = (Map<?, ?>) row;
                context.out().line("  [" + (Boolean.TRUE.equals(check.get("ok")) ? "ok " : "FAIL")
                        + "] " + check.get("check") + ": " + check.get("detail"));
            }
        }
    }

    // ----------------------------------------------------------------- clone

    /**
     * A new local repository holding a server repository's history.
     *
     * <p>Built from the pieces that already exist: initialise, add a remote,
     * fetch, then point a branch at what came back. Doing it that way rather than
     * as a bespoke operation means a clone leaves a repository indistinguishable
     * from one assembled by hand, and there is no separate code path to keep
     * correct.
     */
    public static final class Clone implements Command {

        @Override
        public String name() {
            return "clone";
        }

        @Override
        public String summary() {
            return "Create a local repository from one on a server";
        }

        @Override
        public String usage() {
            return "gitforge clone <url> [directory] [--allow-private-addresses]";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public Object run(Context context, List<String> args) {
            List<String> words = args.stream().filter(arg -> !arg.startsWith("--")).toList();
            if (words.isEmpty() || words.size() > 2) {
                throw CliException.usage(usage());
            }
            String url = words.get(0);
            boolean allowPrivate = args.contains("--allow-private-addresses");
            String validated;
            try {
                validated = RemoteUrl.validate(url, allowPrivate);
            } catch (RuntimeException refused) {
                throw CliException.usage(String.valueOf(refused.getMessage()));
            }
            String directory = words.size() == 2 ? words.get(1) : lastSegment(validated);

            if (context.options().dryRun() || context.options().preview()) {
                return Json.map(
                        "operation", "clone",
                        "url", validated,
                        "directory", directory,
                        "refsWouldMove", List.of(),
                        "refsWouldDelete", List.of(),
                        "databaseRecords", List.of(),
                        "authorization", Json.map("decision", "DEFERRED", "reason", "The remote decides"),
                        "mutated", false);
            }

            Workspace workspace = Workspace.initialise(context.sandbox(), directory, "main");
            new RemoteStore(repositoryRootOf(workspace)).save(new Remote("origin", validated));

            var repository = workspace.repository();
            FetchService fetch = new FetchService(
                    repository.objects(), repository.refs(),
                    new CliRemoteTransport(context), repository.lock());
            FetchService.Result result;
            try {
                result = fetch.fetch(new Remote("origin", validated));
            } catch (RuntimeException failed) {
                throw CliRemoteTransport.translate(failed);
            }

            // Point the default branch at what the remote calls the same name, so
            // the clone lands on a branch rather than on nothing.
            Optional<com.gitforge.vcs.ref.RemoteRef> main = repository.refs().listRemoteRefs().stream()
                    .filter(ref -> ref.remote().equals("origin") && ref.branch().equals("main"))
                    .findFirst();
            main.ifPresent(ref -> {
                repository.branches().createBranch("main", ref.commit());
                context.movedRef("refs/heads/main");
            });

            return Json.map(
                    "url", validated,
                    "directory", workspace.treeRoot().toString(),
                    "receivedObjects", result.receivedObjects(),
                    "updatedRefs", result.updatedRefs(),
                    "checkedOut", main.isPresent() ? "main" : null,
                    "mutated", true);
        }

        private static String lastSegment(String url) {
            String trimmed = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            int slash = trimmed.lastIndexOf('/');
            return slash < 0 ? "repository" : trimmed.substring(slash + 1);
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            if (Boolean.FALSE.equals(data.get("mutated"))) {
                context.out().line("Would clone into " + data.get("directory") + " — nothing was written.");
                return;
            }
            context.out().line("Cloned into " + data.get("directory")
                    + "; " + data.get("receivedObjects") + " object(s)");
        }
    }
}
