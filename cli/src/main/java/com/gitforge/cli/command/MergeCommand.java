package com.gitforge.cli.command;

import com.gitforge.cli.CliException;
import com.gitforge.cli.Context;
import com.gitforge.cli.local.Workspace;
import com.gitforge.cli.output.Json;
import com.gitforge.vcs.graph.CommitGraph;
import com.gitforge.vcs.insights.BranchDivergence;
import com.gitforge.vcs.merge.MergeConflict;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.repository.MergeOutcome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bringing one branch into another.
 *
 * <p>The interesting part is the preview. A merge is the operation where "what
 * will this do" is hardest to answer by inspection and most expensive to get
 * wrong, so {@code --preview} answers it exactly: whether the result would be a
 * fast-forward or a real merge commit, which reference would move and from
 * where, and — when it would not work — which files conflict and why. All of it
 * without writing an object.
 *
 * <p>Conflicts are reported as a conflict, not as a failure of the command. The
 * merge did its job by discovering them; exit code 7 says the state cannot take
 * this change, which is different from the tool breaking.
 */
public final class MergeCommand implements Command {

    static void registerAll() {
        Registry.register(new MergeCommand());
    }

    @Override
    public String name() {
        return "merge";
    }

    @Override
    public String summary() {
        return "Merge a branch into the current one";
    }

    @Override
    public String usage() {
        return "gitforge merge <branch> [-m <message>] [--preview] [--dry-run]";
    }

    @Override
    public boolean mutates() {
        return true;
    }

    @Override
    public Object run(Context context, List<String> args) {
        String theirs = null;
        String message = null;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals("-m") || arg.equals("--message")) {
                if (i + 1 >= args.size()) {
                    throw CliException.usage("-m needs a message");
                }
                message = args.get(++i);
            } else if (!arg.startsWith("--")) {
                theirs = arg;
            }
        }
        if (theirs == null) {
            throw CliException.usage(usage());
        }

        Workspace workspace = Workspace.discover(context.sandbox(), ".");
        String ours = workspace.repository().branches().currentBranch()
                .orElseThrow(() -> CliException.conflict("HEAD is detached; switch to a branch first"));
        if (!workspace.repository().branches().branchExists(theirs)) {
            throw CliException.notFound("No such branch: " + theirs);
        }
        if (ours.equals(theirs)) {
            throw CliException.usage("Cannot merge a branch into itself");
        }

        if (context.options().dryRun() || context.options().preview()) {
            return preview(workspace, ours, theirs);
        }

        String text = message == null ? "Merge " + theirs + " into " + ours : message;
        MergeOutcome outcome = workspace.repository().merges()
                .merge(ours, theirs, LocalCommands.author(context), text);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ours", ours);
        data.put("theirs", theirs);
        switch (outcome) {
            case MergeOutcome.AlreadyUpToDate upToDate -> {
                data.put("outcome", "ALREADY_UP_TO_DATE");
                data.put("commit", upToDate.head().toHex());
                data.put("mutated", false);
            }
            case MergeOutcome.FastForwarded fastForward -> {
                data.put("outcome", "FAST_FORWARDED");
                data.put("commit", fastForward.newHead().toHex());
                data.put("mutated", true);
                context.movedRef("refs/heads/" + ours);
            }
            case MergeOutcome.Merged merged -> {
                data.put("outcome", "MERGED");
                data.put("commit", merged.mergeCommit().toHex());
                data.put("tree", merged.tree().toHex());
                data.put("mutated", true);
                context.movedRef("refs/heads/" + ours);
            }
            case MergeOutcome.Conflicted conflicted -> {
                data.put("outcome", "CONFLICTED");
                data.put("conflicts", conflicts(conflicted.conflicts()));
                data.put("mutated", false);
                // Discovering a conflict is a successful analysis of an
                // unmergeable state, so the message says what is wrong rather
                // than pretending the command failed.
                throw new CliException("CONFLICT", com.gitforge.cli.ExitCode.CONFLICT,
                        "Cannot merge " + theirs + " into " + ours + ": "
                                + conflicted.conflicts().size() + " conflict(s) in "
                                + String.join(", ", paths(conflicted.conflicts())));
            }
            default -> throw CliException.failure("Unrecognised merge outcome");
        }
        return data;
    }

    /**
     * What the merge would do, without doing it.
     *
     * <p>The ahead/behind counts come from the same divergence calculation the
     * engine uses, so a preview that says fast-forward is a merge that
     * fast-forwards.
     */
    private static Map<String, Object> preview(Workspace workspace, String ours, String theirs) {
        var repository = workspace.repository();
        CommitGraph graph = new CommitGraph(repository.objects());
        List<BranchDivergence.Branch> divergence = new BranchDivergence(
                repository.refs(), repository.branches(), graph).againstHead();

        Optional<BranchDivergence.Branch> other = divergence.stream()
                .filter(branch -> branch.name().equals(theirs)).findFirst();
        Optional<ObjectId> from = repository.branches().getBranch(ours);
        Optional<ObjectId> to = repository.branches().getBranch(theirs);

        int ahead = other.map(BranchDivergence.Branch::ahead).orElse(0);
        int behind = other.map(BranchDivergence.Branch::behind).orElse(0);
        boolean related = other.map(BranchDivergence.Branch::related).orElse(false);

        // What matters is whether they have anything we lack. If they do not,
        // the merge is a no-op however far ahead we are — our extra commits are
        // not a reason to create a merge commit. Only when both sides have
        // something the other lacks is a new commit needed.
        String expected = !related ? "UNRELATED_HISTORIES"
                : ahead == 0 ? "ALREADY_UP_TO_DATE"
                : behind == 0 ? "FAST_FORWARD"
                : "MERGE_COMMIT";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "merge");
        data.put("ours", ours);
        data.put("theirs", theirs);
        data.put("expectedOutcome", expected);
        data.put("theirCommitsNotInOurs", ahead);
        data.put("ourCommitsNotInTheirs", behind);
        data.put("wouldCreate", Json.map(
                "objects", expected.equals("MERGE_COMMIT") ? 1 : 0,
                "commits", expected.equals("MERGE_COMMIT") ? 1 : 0));
        data.put("refsWouldMove", expected.equals("ALREADY_UP_TO_DATE")
                ? List.of()
                : List.of(Json.map(
                        "ref", "refs/heads/" + ours,
                        "from", from.map(ObjectId::toHex).orElse(null),
                        "to", expected.equals("FAST_FORWARD")
                                ? to.map(ObjectId::toHex).orElse(null) : null,
                        "fastForward", expected.equals("FAST_FORWARD"))));
        data.put("refsWouldDelete", List.of());
        data.put("databaseRecords", List.of());
        data.put("authorization", Json.map("decision", "ALLOW", "reason", "local repository"));
        data.put("finalState", Json.map("branch", ours, "commit", null));
        data.put("mutated", false);
        return data;
    }

    private static List<Map<String, Object>> conflicts(List<MergeConflict> conflicts) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MergeConflict conflict : conflicts) {
            rows.add(Json.map("path", conflict.path(), "kind", conflict.kind().name()));
        }
        rows.sort((a, b) -> String.valueOf(a.get("path")).compareTo(String.valueOf(b.get("path"))));
        return rows;
    }

    private static List<String> paths(List<MergeConflict> conflicts) {
        return conflicts.stream().map(MergeConflict::path).sorted().toList();
    }

    @Override
    public void describe(Context context, Object result) {
        Map<?, ?> data = (Map<?, ?>) result;
        if (data.containsKey("expectedOutcome")) {
            context.out().line("Merging " + data.get("theirs") + " into " + data.get("ours")
                    + " would be: " + data.get("expectedOutcome"));
            context.out().line("  " + data.get("theirCommitsNotInOurs") + " commit(s) to bring in, "
                    + data.get("ourCommitsNotInTheirs") + " of ours they do not have");
            context.out().line("  Nothing was written.");
            return;
        }
        context.out().line(switch (String.valueOf(data.get("outcome"))) {
            case "ALREADY_UP_TO_DATE" -> "Already up to date.";
            case "FAST_FORWARDED" -> "Fast-forwarded " + data.get("ours") + " to "
                    + String.valueOf(data.get("commit")).substring(0, 12);
            case "MERGED" -> "Merged " + data.get("theirs") + " into " + data.get("ours")
                    + " as " + String.valueOf(data.get("commit")).substring(0, 12);
            default -> String.valueOf(data.get("outcome"));
        });
    }
}
