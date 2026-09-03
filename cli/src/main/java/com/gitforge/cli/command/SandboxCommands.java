package com.gitforge.cli.command;

import com.gitforge.cli.CliException;
import com.gitforge.cli.Context;
import com.gitforge.cli.output.Json;
import com.gitforge.cli.security.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Commands about the boundary itself.
 *
 * <p>A sandbox nobody can inspect is a sandbox nobody can trust. These make the
 * rules and their current state legible: where the root is, what a path resolves
 * to, which environment variables would survive, and — in {@code policy} — what
 * is and is not being claimed.
 */
public final class SandboxCommands {

    private SandboxCommands() {
    }

    /** Where the boundary is and what it is holding. */
    public static final class Status implements Command {

        @Override
        public String name() {
            return "sandbox status";
        }

        @Override
        public String summary() {
            return "Show the sandbox root and the current settings";
        }

        @Override
        public String usage() {
            return "gitforge sandbox status";
        }

        @Override
        public Object run(Context context, List<String> args) {
            Path root = context.sandbox().root();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("root", root.toString());
            data.put("exists", Files.isDirectory(root));
            data.put("readable", Files.isReadable(root));
            data.put("writable", Files.isWritable(root));
            data.put("readOnlyMode", context.options().readOnly());
            data.put("allowedEnvironment",
                    List.copyOf(Environment.filter(context.environment()).keySet()));
            data.put("configFile", context.config().file().toString());
            data.put("auditLog", true);
            return data;
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line("Sandbox root : " + data.get("root"));
            context.out().line("Writable     : " + data.get("writable"));
            context.out().line("Read-only    : " + data.get("readOnlyMode"));
            context.out().line("Environment  : " + data.get("allowedEnvironment"));
        }
    }

    /** Creates the sandbox root. */
    public static final class Init implements Command {

        @Override
        public String name() {
            return "sandbox init";
        }

        @Override
        public String summary() {
            return "Create the sandbox root directory";
        }

        @Override
        public String usage() {
            return "gitforge sandbox init";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public Object run(Context context, List<String> args) {
            Path root = context.sandbox().root();
            boolean existed = Files.isDirectory(root);
            if (!existed) {
                if (context.options().dryRun()) {
                    return Json.map("root", root.toString(), "created", false, "mutated", false);
                }
                try {
                    Files.createDirectories(root);
                } catch (IOException uncreatable) {
                    throw CliException.failure(
                            "Could not create " + root + ": " + uncreatable.getMessage());
                }
            }
            return Json.map(
                    "root", root.toString(),
                    "created", !existed,
                    "mutated", !existed && !context.options().dryRun());
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line(Boolean.TRUE.equals(data.get("created"))
                    ? "Created " + data.get("root")
                    : "Already there: " + data.get("root"));
        }
    }

    /**
     * Checks whether paths are inside the boundary, without acting on them.
     *
     * <p>Useful on its own and useful in a script, but mostly it makes the rule
     * demonstrable: a reader can ask the tool what it thinks about
     * {@code ../../etc/passwd} and get the same answer the enforcement gives.
     */
    public static final class Verify implements Command {

        @Override
        public String name() {
            return "sandbox verify";
        }

        @Override
        public String summary() {
            return "Check whether paths resolve inside the sandbox";
        }

        @Override
        public String usage() {
            return "gitforge sandbox verify <path>...";
        }

        @Override
        public Object run(Context context, List<String> args) {
            if (args.isEmpty()) {
                throw CliException.usage(usage());
            }
            List<Map<String, Object>> results = new ArrayList<>();
            boolean allInside = true;
            for (String candidate : args) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("path", candidate);
                try {
                    Path resolved = context.sandbox().resolve(candidate);
                    row.put("inside", true);
                    row.put("resolved", resolved.toString());
                    row.put("symlink", Files.isSymbolicLink(resolved));
                    row.put("reason", null);
                } catch (CliException refused) {
                    allInside = false;
                    row.put("inside", false);
                    row.put("resolved", null);
                    row.put("symlink", false);
                    row.put("reason", refused.getMessage());
                }
                results.add(row);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("root", context.sandbox().root().toString());
            data.put("allInside", allInside);
            data.put("paths", results);
            // A path outside the sandbox is the answer to the question, not a
            // failure of the command: `sandbox verify` is a question.
            return data;
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            for (Object row : (List<?>) data.get("paths")) {
                Map<?, ?> entry = (Map<?, ?>) row;
                boolean inside = Boolean.TRUE.equals(entry.get("inside"));
                context.out().line((inside ? "inside  " : "OUTSIDE ") + entry.get("path")
                        + (inside ? " -> " + entry.get("resolved") : " (" + entry.get("reason") + ")"));
            }
        }
    }

    /** What the sandbox enforces, and what it does not claim to. */
    public static final class Policy implements Command {

        @Override
        public String name() {
            return "sandbox policy";
        }

        @Override
        public String summary() {
            return "Show what the sandbox enforces, and what it does not claim";
        }

        @Override
        public String usage() {
            return "gitforge sandbox policy";
        }

        @Override
        public Object run(Context context, List<String> args) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("enforced", List.of(
                    "Every path is normalized and must resolve inside the sandbox root",
                    "Paths are re-checked through their real path, so a symlink cannot lead out",
                    "A symlink that stays inside the sandbox is allowed",
                    "No shell is ever invoked, so there is nothing to inject into",
                    "Child processes would inherit only GITFORGE_*, HOME and PATH",
                    "Credentials are stored in a file, never in argv and never in a URL",
                    "Tokens are redacted from stdout, stderr, verbose output and the audit log",
                    "Read-only mode refuses mutations before the command is entered",
                    "Destructive actions require confirmation, and fail closed without a terminal",
                    "The server's own storage is never opened directly; it is reached over HTTP"));
            data.put("notClaimed", List.of(
                    "Operating-system sandboxing: there is no seccomp or AppArmor confinement",
                    "Protection from a caller who already controls this machine",
                    "Cross-process repository locking: the engine lock is in-process only",
                    "Closure of DNS rebinding between validating a remote and connecting to it",
                    "Atomicity between checking a path and opening it"));
            return data;
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line("Enforced:");
            for (Object rule : (List<?>) data.get("enforced")) {
                context.out().line("  - " + rule);
            }
            context.out().line("");
            context.out().line("Not claimed:");
            for (Object gap : (List<?>) data.get("notClaimed")) {
                context.out().line("  - " + gap);
            }
        }
    }

    /** Whether a path is a symbolic link, without following it. */
    static boolean isLink(Path path) {
        return Files.isSymbolicLink(path) || !Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }
}
