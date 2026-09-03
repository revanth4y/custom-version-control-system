package com.gitforge.cli.command;

import com.gitforge.cli.CliException;
import com.gitforge.cli.Context;
import com.gitforge.cli.local.Workspace;
import com.gitforge.cli.output.Json;
import com.gitforge.vcs.graph.CommitGraph;
import com.gitforge.vcs.insights.BranchDivergence;
import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.ref.ReferenceRoots;
import com.gitforge.vcs.ref.TagService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Why, not what.
 *
 * <p>Neither Git nor the GitHub CLI will tell you <em>why</em> a revision
 * resolved where it did, why an object survives collection, or why a push was
 * refused. You infer it, usually wrongly, usually at the worst moment. These
 * commands answer those questions directly, from the same data the engine used
 * to make the decision — so the explanation cannot drift from the behaviour.
 *
 * <p>Explanations are bounded by the same rule as everything else: they describe
 * what the caller may already see. {@code explain rejection} in particular says
 * why access was refused <em>without</em> revealing whether a private repository
 * exists, because an explanation that leaks the thing the refusal was protecting
 * is worse than no explanation.
 */
public final class ExplainCommands {

    private ExplainCommands() {
    }

    static void registerAll() {
        Registry.register(new Revision());
        Registry.register(new Reachability());
        Registry.register(new Protection());
        Registry.register(new Push());
        Registry.register(new Rejection());
    }

    // -------------------------------------------------------------- revision

    /**
     * How a revision string became an object.
     *
     * <p>Resolution walks: a name, then {@code ^} and {@code ~} steps through
     * parents. Each step is shown with what it landed on, so a surprising answer
     * becomes an obvious one.
     */
    public static final class Revision implements Command {

        @Override
        public String name() {
            return "explain revision";
        }

        @Override
        public String summary() {
            return "Show how a revision resolves, step by step";
        }

        @Override
        public String usage() {
            return "gitforge explain revision <revision>";
        }

        @Override
        public Object run(Context context, List<String> args) {
            if (args.size() != 1) {
                throw CliException.usage(usage());
            }
            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            String revision = args.get(0);

            int firstStep = indexOfFirstStep(revision);
            String base = firstStep < 0 ? revision : revision.substring(0, firstStep);
            String steps = firstStep < 0 ? "" : revision.substring(firstStep);

            List<Map<String, Object>> trace = new ArrayList<>();
            trace.add(Json.map(
                    "step", "base",
                    "input", base,
                    "kind", kindOf(workspace, base),
                    "resolvesTo", workspace.repository().reader().resolve(base)
                            .map(ObjectId::toHex).orElse(null)));

            // Each suffix step is resolved cumulatively, so the trace shows the
            // object after every ^ or ~ rather than only the final answer.
            StringBuilder progressive = new StringBuilder(base);
            int i = 0;
            while (i < steps.length()) {
                char operator = steps.charAt(i);
                int j = i + 1;
                while (j < steps.length() && Character.isDigit(steps.charAt(j))) {
                    j++;
                }
                String step = steps.substring(i, j);
                progressive.append(step);
                trace.add(Json.map(
                        "step", operator == '~' ? "first-parent walk" : "parent selection",
                        "input", step,
                        "kind", operator == '~' ? "ANCESTOR" : "PARENT",
                        "resolvesTo", workspace.repository().reader().resolve(progressive.toString())
                                .map(ObjectId::toHex).orElse(null)));
                i = j;
            }

            Optional<ObjectId> resolved = workspace.repository().reader().resolve(revision);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("revision", revision);
            data.put("resolved", resolved.map(ObjectId::toHex).orElse(null));
            data.put("resolvedShort", resolved.map(id -> id.toHex().substring(0, 12)).orElse(null));
            data.put("trace", trace);
            resolved.flatMap(id -> workspace.repository().reader().commit(id))
                    .ifPresent(commit -> data.put("commit", Json.map(
                            "message", commit.message().strip(),
                            "author", commit.author().name(),
                            "parents", commit.parents().size())));
            if (resolved.isEmpty()) {
                data.put("reason", "The base name does not exist, or a step walked past the root commit");
            }
            return data;
        }

        private static int indexOfFirstStep(String revision) {
            for (int i = 0; i < revision.length(); i++) {
                char c = revision.charAt(i);
                if (c == '^' || c == '~') {
                    return i;
                }
            }
            return -1;
        }

        private static String kindOf(Workspace workspace, String base) {
            if (base.equals("HEAD")) {
                return "HEAD";
            }
            if (workspace.repository().branches().branchExists(base)) {
                return "BRANCH";
            }
            if (workspace.repository().tags().tagExists(base)) {
                return "TAG";
            }
            return base.matches("[0-9a-fA-F]{4,40}") ? "OBJECT_ID" : "UNKNOWN";
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line(data.get("revision") + " resolves to "
                    + String.valueOf(Optional.ofNullable(data.get("resolved")).orElse("(nothing)")));
            for (Object row : (List<?>) data.get("trace")) {
                Map<?, ?> step = (Map<?, ?>) row;
                context.out().line("  " + step.get("input") + "  [" + step.get("kind") + "]  -> "
                        + String.valueOf(Optional.ofNullable(step.get("resolvesTo")).orElse("(unresolved)")));
            }
            if (data.get("reason") != null) {
                context.out().line("  " + data.get("reason"));
            }
        }
    }

    // ---------------------------------------------------------- reachability

    /** Whether an object survives collection, and by whose authority. */
    public static final class Reachability implements Command {

        @Override
        public String name() {
            return "explain reachability";
        }

        @Override
        public String summary() {
            return "Say whether an object is reachable, and from which root";
        }

        @Override
        public String usage() {
            return "gitforge explain reachability <object-id>";
        }

        @Override
        public Object run(Context context, List<String> args) {
            if (args.size() != 1) {
                throw CliException.usage(usage());
            }
            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            var repository = workspace.repository();
            ObjectId target = repository.reader().resolve(args.get(0))
                    .orElseThrow(() -> CliException.notFound("No such object: " + args.get(0)));

            CommitGraph graph = new CommitGraph(repository.objects());
            List<ObjectId> roots = ReferenceRoots.of(repository.refs(), workspace.workTreeState());

            List<Map<String, Object>> protecting = new ArrayList<>();
            for (ObjectId root : roots.stream().distinct().toList()) {
                Set<ObjectId> ancestry;
                try {
                    ancestry = graph.ancestorsOf(root);
                } catch (RuntimeException notACommit) {
                    // A root may be a tree (the worktree) or a tag object; those
                    // are roots without an ancestry walk of their own.
                    ancestry = Set.of(root);
                }
                if (ancestry.contains(target)) {
                    protecting.add(Json.map(
                            "root", root.toHex(),
                            "kind", describeRoot(workspace, root),
                            "distance", ancestry.size()));
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("object", target.toHex());
            data.put("reachable", !protecting.isEmpty());
            data.put("rootsExamined", roots.stream().distinct().count());
            data.put("protectedBy", protecting);
            data.put("explanation", protecting.isEmpty()
                    ? "No root reaches this object, so collection would consider it garbage."
                    : "Collection keeps this object because " + protecting.size()
                            + " root(s) reach it.");
            return data;
        }

        /** Which kind of reference a root came from, named rather than guessed. */
        static String describeRoot(Workspace workspace, ObjectId root) {
            var refs = workspace.repository().refs();
            for (String branch : refs.listBranches()) {
                if (refs.getBranch(branch).filter(root::equals).isPresent()) {
                    return "branch " + branch;
                }
            }
            for (String tag : refs.listTags()) {
                if (refs.getTag(tag).filter(root::equals).isPresent()) {
                    return "tag " + tag;
                }
            }
            if (refs.resolveHead().filter(root::equals).isPresent()) {
                return "HEAD";
            }
            if (workspace.workTreeState().materializedTree().filter(root::equals).isPresent()) {
                return "working tree";
            }
            return "remote-tracking ref";
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line(String.valueOf(data.get("explanation")));
            for (Object row : (List<?>) data.get("protectedBy")) {
                Map<?, ?> root = (Map<?, ?>) row;
                context.out().line("  via " + root.get("kind") + " (" + root.get("root") + ")");
            }
        }
    }

    // ------------------------------------------------------------ protection

    /** The same question from the other direction: what is a root, and why. */
    public static final class Protection implements Command {

        @Override
        public String name() {
            return "explain protection";
        }

        @Override
        public String summary() {
            return "List the roots that protect objects from collection";
        }

        @Override
        public String usage() {
            return "gitforge explain protection";
        }

        @Override
        public Object run(Context context, List<String> args) {
            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            List<ObjectId> roots = ReferenceRoots.of(
                    workspace.repository().refs(), workspace.workTreeState());

            List<Map<String, Object>> rows = new ArrayList<>();
            for (ObjectId root : roots.stream().distinct().toList()) {
                rows.add(Json.map(
                        "object", root.toHex(),
                        "kind", Reachability.describeRoot(workspace, root)));
            }
            return Json.map(
                    "rootCount", roots.size(),
                    "distinctRoots", rows.size(),
                    "roots", rows,
                    "explanation",
                    "Collection keeps every object reachable from one of these. The same set is "
                            + "used by statistics, so a commit that is protected is also counted.");
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line(data.get("distinctRoots") + " distinct root(s):");
            for (Object row : (List<?>) data.get("roots")) {
                Map<?, ?> root = (Map<?, ?>) row;
                context.out().line("  " + root.get("kind") + "  " + root.get("object"));
            }
            context.out().line("");
            context.out().line(String.valueOf(data.get("explanation")));
        }
    }

    // ------------------------------------------------------------------ push

    /**
     * Why a branch cannot be pushed.
     *
     * <p>Answered locally, from divergence, so it works before contacting a
     * remote and cannot be wrong about the reason after it does.
     */
    public static final class Push implements Command {

        @Override
        public String name() {
            return "explain push";
        }

        @Override
        public String summary() {
            return "Explain whether a branch would fast-forward, and why not";
        }

        @Override
        public String usage() {
            return "gitforge explain push <branch> [--against <branch>]";
        }

        @Override
        public Object run(Context context, List<String> args) {
            List<String> names = args.stream().filter(arg -> !arg.startsWith("--")).toList();
            if (names.isEmpty()) {
                throw CliException.usage(usage());
            }
            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            var repository = workspace.repository();
            String branch = names.get(0);
            if (!repository.branches().branchExists(branch)) {
                throw CliException.notFound("No such branch: " + branch);
            }

            CommitGraph graph = new CommitGraph(repository.objects());
            List<BranchDivergence.Branch> divergence = new BranchDivergence(
                    repository.refs(), repository.branches(), graph).againstHead();
            BranchDivergence.Branch row = divergence.stream()
                    .filter(candidate -> candidate.name().equals(branch))
                    .findFirst()
                    .orElseThrow(() -> CliException.notFound("No such branch: " + branch));

            boolean fastForward = row.behind() == 0 && row.related();
            String reason;
            if (!row.related()) {
                reason = "The two histories share no commit at all. A push would not be a "
                        + "fast-forward because there is nothing to fast-forward from.";
            } else if (row.behind() > 0) {
                reason = "The target has " + row.behind() + " commit(s) this branch does not "
                        + "contain, so moving the reference forward would drop them. Merge or "
                        + "rebase them in first.";
            } else if (row.ahead() == 0) {
                reason = "Nothing to push: the target already has every commit this branch has.";
            } else {
                reason = "This branch contains every commit the target has, plus " + row.ahead()
                        + " more, so the reference can move forward without losing anything.";
            }

            return Json.map(
                    "branch", branch,
                    "tip", row.tip().toHex(),
                    "ahead", row.ahead(),
                    "behind", row.behind(),
                    "relatedHistories", row.related(),
                    "wouldFastForward", fastForward,
                    "explanation", reason);
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line(data.get("branch") + ": " + data.get("ahead") + " ahead, "
                    + data.get("behind") + " behind");
            context.out().line("Fast-forward: " + data.get("wouldFastForward"));
            context.out().line(String.valueOf(data.get("explanation")));
        }
    }

    // ------------------------------------------------------------- rejection

    /**
     * What an authorization refusal means — without confirming what was hidden.
     *
     * <p>This one is deliberately careful. The server answers 404 for a private
     * repository the caller may not see, precisely so that "it exists but you
     * cannot have it" and "it does not exist" are indistinguishable. An explain
     * command that resolved that ambiguity would hand back exactly what the 404
     * was protecting, so this explains the <em>rules</em> and never queries the
     * resource.
     */
    public static final class Rejection implements Command {

        @Override
        public String name() {
            return "explain rejection";
        }

        @Override
        public String summary() {
            return "Explain what an authorization failure means";
        }

        @Override
        public String usage() {
            return "gitforge explain rejection [code]";
        }

        @Override
        public Object run(Context context, List<String> args) {
            String code = args.isEmpty() ? null : args.get(0).toUpperCase(java.util.Locale.ROOT);

            Map<String, String> meanings = new LinkedHashMap<>();
            meanings.put("UNAUTHENTICATED",
                    "No credentials were sent, or the token has expired. Run 'gitforge auth login'.");
            meanings.put("FORBIDDEN",
                    "You are authenticated and this repository exists, but the operation is "
                            + "owner-only. Writes require ownership.");
            meanings.put("NOT_FOUND",
                    "Either the repository does not exist, or it is private and not yours. "
                            + "These are answered identically on purpose: distinguishing them "
                            + "would reveal that a private repository exists. Nothing here can "
                            + "tell you which it was.");
            meanings.put("READ_ONLY",
                    "The CLI is running read-only, from --read-only or GITFORGE_READ_ONLY. "
                            + "The refusal happened before anything was attempted.");
            meanings.put("SANDBOX_VIOLATION",
                    "A path resolved outside the sandbox root, directly or through a symlink.");
            meanings.put("CONFIRMATION_REQUIRED",
                    "A destructive action needed consent and there was no terminal to ask. "
                            + "Pass --yes to consent in advance.");

            if (code != null && !meanings.containsKey(code)) {
                throw CliException.usage(
                        "Unknown code '" + code + "'. Known: " + String.join(", ", meanings.keySet()));
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            meanings.forEach((key, meaning) -> {
                if (code == null || code.equals(key)) {
                    rows.add(Json.map("code", key, "meaning", meaning));
                }
            });
            return Json.map(
                    "codes", rows,
                    "note", "These are explanations of the rules. No repository was queried, so "
                            + "nothing here reveals whether any particular resource exists.");
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            for (Object row : (List<?>) data.get("codes")) {
                Map<?, ?> entry = (Map<?, ?>) row;
                context.out().line(entry.get("code") + ":");
                context.out().line("  " + entry.get("meaning"));
            }
            context.out().line("");
            context.out().line(String.valueOf(data.get("note")));
        }
    }
}
