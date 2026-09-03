package com.gitforge.cli.command;

import com.gitforge.cli.CliException;
import com.gitforge.cli.Context;
import com.gitforge.cli.local.Workspace;
import com.gitforge.cli.output.Json;
import com.gitforge.vcs.diff.TreeChange;
import com.gitforge.vcs.diff.TreeDiff;
import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.FileMode;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Signature;
import com.gitforge.vcs.repository.FileChange;
import com.gitforge.vcs.worktree.WorkingTreeStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The commands that need no server.
 *
 * <p>All of these run against the engine and a working tree inside the sandbox.
 * That is the half of GitForge that was built and then deliberately left unwired
 * — a server has no checkout for anyone to edit — and it is what makes the CLI
 * more than a wrapper around HTTP calls: {@code init}, {@code add} and
 * {@code commit} here do the same content-addressed work the server does, on a
 * repository that belongs to the person running them.
 */
public final class LocalCommands {

    private LocalCommands() {
    }

    /** Registers this group. */
    static void registerAll() {
        Registry.register(new Init());
        Registry.register(new Status());
        Registry.register(new Add());
        Registry.register(new CommitCommand());
        Registry.register(new Log());
        Registry.register(new Show());
        Registry.register(new Diff());
    }

    // --------------------------------------------------------------- helpers

    /** The author to record. Configurable, with a defensible default. */
    static Signature author(Context context) {
        String name = context.environment().getOrDefault("GITFORGE_AUTHOR_NAME", "gitforge");
        String email = context.environment().getOrDefault("GITFORGE_AUTHOR_EMAIL", "gitforge@localhost");
        return Signature.of(name, email, Instant.now());
    }

    static Map<String, Object> describeCommit(Commit commit) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("commit", commit.id().toHex());
        row.put("shortCommit", commit.id().toHex().substring(0, 12));
        row.put("message", commit.message().strip());
        row.put("author", commit.author().name());
        row.put("email", commit.author().email());
        row.put("timestamp", Json.time(commit.author().timestamp()));
        row.put("parents", commit.parents().stream().map(ObjectId::toHex).toList());
        row.put("merge", commit.isMerge());
        return row;
    }

    // ------------------------------------------------------------------ init

    public static final class Init implements Command {

        @Override
        public String name() {
            return "init";
        }

        @Override
        public String summary() {
            return "Create a repository in a directory";
        }

        @Override
        public String usage() {
            return "gitforge init [directory] [--branch <name>]";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public Object run(Context context, List<String> args) {
            String directory = ".";
            String branch = "main";
            for (int i = 0; i < args.size(); i++) {
                if (args.get(i).equals("--branch")) {
                    if (i + 1 >= args.size()) {
                        throw CliException.usage("--branch needs a name");
                    }
                    branch = args.get(++i);
                } else {
                    directory = args.get(i);
                }
            }

            Path tree = context.path(directory);
            if (context.options().dryRun()) {
                return Json.map(
                        "path", tree.toString(),
                        "defaultBranch", branch,
                        "created", false,
                        "mutated", false);
            }
            Workspace workspace = Workspace.initialise(context.sandbox(), directory, branch);
            return Json.map(
                    "path", workspace.treeRoot().toString(),
                    "defaultBranch", branch,
                    "created", true,
                    "mutated", true);
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line("Initialised a repository at " + data.get("path")
                    + " on branch " + data.get("defaultBranch"));
        }
    }

    // ---------------------------------------------------------------- status

    public static final class Status implements Command {

        @Override
        public String name() {
            return "status";
        }

        @Override
        public String summary() {
            return "Show the branch, staged paths and working tree changes";
        }

        @Override
        public String usage() {
            return "gitforge status";
        }

        @Override
        public Object run(Context context, List<String> args) {
            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            var branches = workspace.repository().branches();
            Optional<String> branch = branches.currentBranch();
            Optional<ObjectId> head = branches.headCommit();

            WorkingTreeStatus status = head
                    .flatMap(commit -> workspace.repository().reader().commit(commit))
                    .map(commit -> workspace.workingTree().status(commit.tree()))
                    .orElseGet(() -> untrackedOnly(workspace));

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("root", workspace.treeRoot().toString());
            data.put("branch", branch.orElse(null));
            data.put("head", head.map(ObjectId::toHex).orElse(null));
            data.put("staged", List.copyOf(workspace.index().staged()));
            data.put("modified", status.modified());
            data.put("deleted", status.deleted());
            data.put("untracked", withoutMetadata(status.untracked()));
            data.put("clean", status.isClean() && workspace.index().staged().isEmpty());
            return data;
        }

        /**
         * Before the first commit there is no tree to compare against, so every
         * file is untracked. Asking the engine for a status against a tree that
         * does not exist would be asking it a question with no answer.
         */
        private static WorkingTreeStatus untrackedOnly(Workspace workspace) {
            return new WorkingTreeStatus(
                    List.of(), List.of(), new ArrayList<>(new TreeSet<>(workspace.workingTree().listFiles())));
        }

        private static List<String> withoutMetadata(List<String> paths) {
            return paths.stream().filter(path -> !Workspace.isMetadata(path)).toList();
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line("On branch " + Optional.ofNullable(data.get("branch")).orElse("(detached)"));
            if (data.get("head") == null) {
                context.out().line("No commits yet");
            }
            section(context, "Staged", (List<?>) data.get("staged"));
            section(context, "Modified", (List<?>) data.get("modified"));
            section(context, "Deleted", (List<?>) data.get("deleted"));
            section(context, "Untracked", (List<?>) data.get("untracked"));
            if (Boolean.TRUE.equals(data.get("clean"))) {
                context.out().line("Nothing to commit; the working tree is clean.");
            }
        }

        private static void section(Context context, String title, List<?> paths) {
            if (paths == null || paths.isEmpty()) {
                return;
            }
            context.out().line("");
            context.out().line(title + ":");
            paths.forEach(path -> context.out().line("  " + path));
        }
    }

    // ------------------------------------------------------------------- add

    public static final class Add implements Command {

        @Override
        public String name() {
            return "add";
        }

        @Override
        public String summary() {
            return "Stage paths for the next commit";
        }

        @Override
        public String usage() {
            return "gitforge add <path>...";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public Object run(Context context, List<String> args) {
            if (args.isEmpty()) {
                throw CliException.usage(usage());
            }
            Workspace workspace = Workspace.discover(context.sandbox(), ".");

            List<String> paths = new ArrayList<>();
            for (String candidate : args) {
                // Containment first: a path that leaves the sandbox is refused
                // before it is opened, read, or even tested for existence.
                Path resolved = context.path(candidate);
                if (!resolved.startsWith(workspace.treeRoot())) {
                    throw CliException.sandbox(
                            "Path is outside this repository's working tree: " + candidate);
                }
                if (!Files.exists(resolved)) {
                    throw CliException.notFound("No such file: " + candidate);
                }
                if (Files.isDirectory(resolved)) {
                    paths.addAll(filesUnder(workspace, resolved));
                } else {
                    paths.add(workspace.relativise(resolved));
                }
            }
            paths.removeIf(Workspace::isMetadata);
            if (paths.isEmpty()) {
                throw CliException.usage("Nothing to stage: every path given is repository metadata");
            }

            if (context.options().dryRun()) {
                return Json.map("wouldStage", paths, "staged", List.of(), "mutated", false);
            }
            List<String> added = workspace.index().add(paths);
            return Json.map(
                    "staged", added,
                    "alreadyStaged", paths.stream().filter(path -> !added.contains(path)).toList(),
                    "mutated", !added.isEmpty());
        }

        private static List<String> filesUnder(Workspace workspace, Path directory) {
            try (var walk = Files.walk(directory)) {
                return walk.filter(Files::isRegularFile)
                        .map(workspace::relativise)
                        .filter(path -> !Workspace.isMetadata(path))
                        .sorted()
                        .toList();
            } catch (IOException unreadable) {
                throw CliException.failure("Could not read " + directory + ": " + unreadable.getMessage());
            }
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            List<?> staged = data.get("staged") instanceof List<?> list ? list : List.of();
            List<?> would = data.get("wouldStage") instanceof List<?> list ? list : List.of();
            List<?> shown = staged.isEmpty() ? would : staged;
            if (shown.isEmpty()) {
                context.out().line("Nothing new to stage.");
                return;
            }
            context.out().line((staged.isEmpty() ? "Would stage " : "Staged ") + shown.size() + ":");
            shown.forEach(path -> context.out().line("  " + path));
        }
    }

    // ---------------------------------------------------------------- commit

    public static final class CommitCommand implements Command {

        @Override
        public String name() {
            return "commit";
        }

        @Override
        public String summary() {
            return "Record the staged paths as a commit";
        }

        @Override
        public String usage() {
            return "gitforge commit -m <message>";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public Object run(Context context, List<String> args) {
            String message = null;
            for (int i = 0; i < args.size(); i++) {
                if (args.get(i).equals("-m") || args.get(i).equals("--message")) {
                    if (i + 1 >= args.size()) {
                        throw CliException.usage("-m needs a message");
                    }
                    message = args.get(++i);
                }
            }
            if (message == null || message.isBlank()) {
                throw CliException.usage(usage());
            }

            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            List<String> staged = List.copyOf(workspace.index().staged());
            if (staged.isEmpty()) {
                throw CliException.usage("Nothing staged. Use 'gitforge add <path>' first.");
            }
            String branch = workspace.repository().branches().currentBranch()
                    .orElseThrow(() -> CliException.conflict(
                            "HEAD is detached; commit on a branch instead"));

            List<FileChange> changes = new ArrayList<>();
            for (String path : staged) {
                Path file = workspace.treeRoot().resolve(path);
                if (Files.isRegularFile(file)) {
                    changes.add(new FileChange.Put(path, read(file), FileMode.REGULAR_FILE));
                } else {
                    changes.add(new FileChange.Delete(path));
                }
            }

            if (context.options().dryRun() || context.options().preview()) {
                return preview(context, workspace, branch, staged, changes);
            }

            ObjectId commit = workspace.repository().commits()
                    .commit(branch, changes, author(context), message);
            workspace.index().clear();
            context.movedRef("refs/heads/" + branch);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("commit", commit.toHex());
            data.put("branch", branch);
            data.put("files", staged.size());
            data.put("message", message);
            data.put("mutated", true);
            return data;
        }

        /**
         * What the commit would do, computed without doing it.
         *
         * <p>The counts come from the same staged set the real path would use,
         * so a preview that says three files is a commit that records three
         * files. Nothing is written: no blob, no tree, no commit object, and the
         * branch does not move.
         */
        private static Map<String, Object> preview(
                Context context, Workspace workspace, String branch,
                List<String> staged, List<FileChange> changes) {

            Optional<ObjectId> from = workspace.repository().branches().getBranch(branch);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", "commit");
            data.put("wouldCreate", Json.map(
                    "objects", changes.size() + 1 + 1,
                    "commits", 1,
                    "note", "at most: identical content is stored once and costs nothing"));
            data.put("refsWouldMove", List.of(Json.map(
                    "ref", "refs/heads/" + branch,
                    "from", from.map(ObjectId::toHex).orElse(null),
                    "to", null,
                    "fastForward", true)));
            data.put("refsWouldDelete", List.of());
            data.put("databaseRecords", List.of());
            data.put("authorization", Json.map("decision", "ALLOW", "reason", "local repository"));
            data.put("files", staged);
            data.put("finalState", Json.map("branch", branch, "commit", null));
            data.put("mutated", false);
            return data;
        }

        private static byte[] read(Path file) {
            try {
                return Files.readAllBytes(file);
            } catch (IOException unreadable) {
                throw CliException.failure("Could not read " + file + ": " + unreadable.getMessage());
            }
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            if (Boolean.FALSE.equals(data.get("mutated")) && data.containsKey("operation")) {
                context.out().line("Would commit " + ((List<?>) data.get("files")).size()
                        + " file(s) on " + ((Map<?, ?>) data.get("finalState")).get("branch")
                        + " — nothing was written.");
                return;
            }
            context.out().line("[" + data.get("branch") + " "
                    + String.valueOf(data.get("commit")).substring(0, 12) + "] " + data.get("message"));
            context.out().line(data.get("files") + " file(s) recorded");
        }
    }

    // ------------------------------------------------------------------- log

    public static final class Log implements Command {

        @Override
        public String name() {
            return "log";
        }

        @Override
        public String summary() {
            return "Show commit history";
        }

        @Override
        public String usage() {
            return "gitforge log [revision] [--limit <n>]";
        }

        @Override
        public Object run(Context context, List<String> args) {
            String revision = "HEAD";
            int limit = 20;
            for (int i = 0; i < args.size(); i++) {
                if (args.get(i).equals("--limit")) {
                    if (i + 1 >= args.size()) {
                        throw CliException.usage("--limit needs a number");
                    }
                    limit = parsePositive(args.get(++i));
                } else {
                    revision = args.get(i);
                }
            }

            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            if (!workspace.repository().reader().hasCommits()) {
                return Json.map("revision", revision, "count", 0, "commits", List.of());
            }
            List<Map<String, Object>> commits = workspace.repository().reader()
                    .history(revision, limit).stream()
                    .map(LocalCommands::describeCommit)
                    .toList();
            return Json.map("revision", revision, "count", commits.size(), "commits", commits);
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            List<?> commits = (List<?>) data.get("commits");
            if (commits.isEmpty()) {
                context.out().line("No commits yet.");
                return;
            }
            for (Object entry : commits) {
                Map<?, ?> commit = (Map<?, ?>) entry;
                context.out().line(commit.get("shortCommit") + "  " + commit.get("message"));
                context.out().line("        " + commit.get("author") + "  " + commit.get("timestamp"));
            }
        }
    }

    // ------------------------------------------------------------------ show

    public static final class Show implements Command {

        @Override
        public String name() {
            return "show";
        }

        @Override
        public String summary() {
            return "Show one commit and what it changed";
        }

        @Override
        public String usage() {
            return "gitforge show [revision]";
        }

        @Override
        public Object run(Context context, List<String> args) {
            String revision = args.isEmpty() ? "HEAD" : args.get(0);
            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            ObjectId id = workspace.repository().reader().resolve(revision)
                    .orElseThrow(() -> CliException.notFound("No such revision: " + revision));
            Commit commit = workspace.repository().reader().commit(id)
                    .orElseThrow(() -> CliException.notFound("Not a commit: " + revision));

            Map<String, Object> data = new LinkedHashMap<>(describeCommit(commit));
            data.put("changes", changes(workspace.repository().reader().changesIn(id)));
            return data;
        }

        /**
         * A change, flattened to a path and a word.
         *
         * <p>The engine models changes as a sealed hierarchy so a caller has to
         * handle every case; the CLI only needs to name them, so this is the one
         * place the pattern match happens.
         */
        static List<Map<String, Object>> changes(TreeDiff diff) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (TreeChange change : diff.changes()) {
                String kind = switch (change) {
                    case TreeChange.Added ignored -> "ADDED";
                    case TreeChange.Deleted ignored -> "DELETED";
                    case TreeChange.Modified ignored -> "MODIFIED";
                };
                rows.add(Json.map("path", change.path(), "kind", kind));
            }
            rows.sort((a, b) -> String.valueOf(a.get("path")).compareTo(String.valueOf(b.get("path"))));
            return rows;
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line("commit " + data.get("commit"));
            context.out().line("Author: " + data.get("author") + " <" + data.get("email") + ">");
            context.out().line("Date:   " + data.get("timestamp"));
            context.out().line("");
            context.out().line("    " + data.get("message"));
            context.out().line("");
            for (Object row : (List<?>) data.get("changes")) {
                Map<?, ?> change = (Map<?, ?>) row;
                context.out().line("  " + change.get("kind") + "  " + change.get("path"));
            }
        }
    }

    // ------------------------------------------------------------------ diff

    public static final class Diff implements Command {

        @Override
        public String name() {
            return "diff";
        }

        @Override
        public String summary() {
            return "Compare two revisions";
        }

        @Override
        public String usage() {
            return "gitforge diff <from> <to>";
        }

        @Override
        public Object run(Context context, List<String> args) {
            if (args.size() != 2) {
                throw CliException.usage(usage());
            }
            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            TreeDiff diff = workspace.repository().reader().compare(args.get(0), args.get(1))
                    .orElseThrow(() -> CliException.notFound(
                            "Could not compare " + args.get(0) + " and " + args.get(1)));
            List<Map<String, Object>> changes = Show.changes(diff);
            return Json.map(
                    "from", args.get(0),
                    "to", args.get(1),
                    "count", changes.size(),
                    "changes", changes);
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            List<?> changes = (List<?>) data.get("changes");
            if (changes.isEmpty()) {
                context.out().line("No differences.");
                return;
            }
            for (Object row : changes) {
                Map<?, ?> change = (Map<?, ?>) row;
                context.out().line(change.get("kind") + "  " + change.get("path"));
            }
        }
    }

    static int parsePositive(String raw) {
        try {
            int value = Integer.parseInt(raw);
            if (value <= 0) {
                throw CliException.usage("Expected a positive number, got " + raw);
            }
            return value;
        } catch (NumberFormatException notANumber) {
            throw CliException.usage("Expected a number, got " + raw);
        }
    }
}
