package com.gitforge.cli.command;

import com.gitforge.cli.CliException;
import com.gitforge.cli.Context;
import com.gitforge.cli.local.Workspace;
import com.gitforge.cli.output.Json;
import com.gitforge.vcs.insights.BranchDivergence;
import com.gitforge.vcs.graph.CommitGraph;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Tag;
import com.gitforge.vcs.ref.TagService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Branches and tags: the names a repository keeps.
 *
 * <p>Deleting either removes a name and never an object. That is worth stating
 * because it is the opposite of what the word suggests: the commits a deleted
 * branch pointed at are still there, still reachable by id, and are removed only
 * by an explicit collection with the grace period and the exclusive lock that
 * implies. A delete here is cheap and, in the ways that matter, reversible.
 */
public final class RefCommands {

    private RefCommands() {
    }

    static void registerAll() {
        Registry.register(new BranchList());
        Registry.register(new BranchShow());
        Registry.register(new BranchCreate());
        Registry.register(new BranchDelete());
        Registry.register(new BranchRename());
        Registry.register(new BranchCompare());
        Registry.register(new Switch());
        Registry.register(new TagList());
        Registry.register(new TagShow());
        Registry.register(new TagCreate());
        Registry.register(new TagDelete());
        Registry.register(new TagPeel());
        Registry.register(new TagChain());
    }

    // -------------------------------------------------------------- branches

    public static final class BranchList implements Command {

        @Override
        public String name() {
            return "branch list";
        }

        @Override
        public String summary() {
            return "List branches and where each one points";
        }

        @Override
        public String usage() {
            return "gitforge branch list";
        }

        @Override
        public Object run(Context context, List<String> args) {
            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            var branches = workspace.repository().branches();
            Optional<String> current = branches.currentBranch();

            List<Map<String, Object>> rows = new ArrayList<>();
            for (String branch : branches.listBranches()) {
                rows.add(Json.map(
                        "name", branch,
                        "commit", branches.getBranch(branch).map(ObjectId::toHex).orElse(null),
                        "current", current.map(branch::equals).orElse(false)));
            }
            return Json.map("count", rows.size(), "current", current.orElse(null), "branches", rows);
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            for (Object row : (List<?>) data.get("branches")) {
                Map<?, ?> branch = (Map<?, ?>) row;
                String marker = Boolean.TRUE.equals(branch.get("current")) ? "* " : "  ";
                context.out().line(marker + branch.get("name") + "  "
                        + String.valueOf(branch.get("commit")).substring(0, 12));
            }
        }
    }

    public static final class BranchShow implements Command {

        @Override
        public String name() {
            return "branch show";
        }

        @Override
        public String summary() {
            return "Show one branch and its distance from HEAD";
        }

        @Override
        public String usage() {
            return "gitforge branch show <name>";
        }

        @Override
        public Object run(Context context, List<String> args) {
            if (args.size() != 1) {
                throw CliException.usage(usage());
            }
            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            String name = args.get(0);
            ObjectId tip = workspace.repository().branches().getBranch(name)
                    .orElseThrow(() -> CliException.notFound("No such branch: " + name));

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("name", name);
            data.put("commit", tip.toHex());
            data.put("current", workspace.repository().branches().currentBranch()
                    .map(name::equals).orElse(false));
            divergence(workspace, name).ifPresent(row -> {
                data.put("ahead", row.get("ahead"));
                data.put("behind", row.get("behind"));
                data.put("related", row.get("related"));
            });
            return data;
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line(data.get("name") + "  " + data.get("commit"));
            if (data.containsKey("ahead")) {
                context.out().line("  " + data.get("ahead") + " ahead, " + data.get("behind") + " behind HEAD");
            }
        }
    }

    /** One branch's divergence from HEAD, if HEAD resolves at all. */
    private static Optional<Map<String, Object>> divergence(Workspace workspace, String branch) {
        var repository = workspace.repository();
        CommitGraph graph = new CommitGraph(repository.objects());
        List<BranchDivergence.Branch> all = new BranchDivergence(
                repository.refs(), repository.branches(), graph).againstHead();
        return all.stream()
                .filter(row -> row.name().equals(branch))
                .findFirst()
                .map(row -> Json.map(
                        "ahead", row.ahead(), "behind", row.behind(), "related", row.related()));
    }

    public static final class BranchCreate implements Command {

        @Override
        public String name() {
            return "branch create";
        }

        @Override
        public String summary() {
            return "Create a branch at a revision";
        }

        @Override
        public String usage() {
            return "gitforge branch create <name> [start-point]";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public Object run(Context context, List<String> args) {
            if (args.isEmpty() || args.size() > 2) {
                throw CliException.usage(usage());
            }
            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            String name = args.get(0);
            String startPoint = args.size() == 2 ? args.get(1) : "HEAD";

            if (workspace.repository().branches().branchExists(name)) {
                throw CliException.conflict("A branch called " + name + " already exists");
            }
            ObjectId start = workspace.repository().reader().resolve(startPoint)
                    .orElseThrow(() -> CliException.notFound("No such revision: " + startPoint));

            if (context.options().dryRun() || context.options().preview()) {
                return Json.map(
                        "operation", "branch create",
                        "wouldCreate", Json.map("objects", 0, "commits", 0),
                        "refsWouldMove", List.of(Json.map(
                                "ref", "refs/heads/" + name,
                                "from", null,
                                "to", start.toHex(),
                                "fastForward", true)),
                        "refsWouldDelete", List.of(),
                        "databaseRecords", List.of(),
                        "authorization", Json.map("decision", "ALLOW", "reason", "local repository"),
                        "finalState", Json.map("branch", name, "commit", start.toHex()),
                        "mutated", false);
            }
            workspace.repository().branches().createBranch(name, start);
            context.movedRef("refs/heads/" + name);
            return Json.map("name", name, "commit", start.toHex(), "from", startPoint, "mutated", true);
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            if (Boolean.FALSE.equals(data.get("mutated"))) {
                context.out().line("Would create " + data.get("operation") + " — nothing was written.");
                return;
            }
            context.out().line("Created branch " + data.get("name") + " at "
                    + String.valueOf(data.get("commit")).substring(0, 12));
        }
    }

    public static final class BranchDelete implements Command {

        @Override
        public String name() {
            return "branch delete";
        }

        @Override
        public String summary() {
            return "Delete a branch (the commits it named are not removed)";
        }

        @Override
        public String usage() {
            return "gitforge branch delete <name> [--yes]";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public Object run(Context context, List<String> args) {
            List<String> names = args.stream().filter(arg -> !arg.startsWith("--")).toList();
            if (names.size() != 1) {
                throw CliException.usage(usage());
            }
            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            String name = names.get(0);
            ObjectId tip = workspace.repository().branches().getBranch(name)
                    .orElseThrow(() -> CliException.notFound("No such branch: " + name));

            if (context.options().dryRun() || context.options().preview()) {
                return Json.map(
                        "operation", "branch delete",
                        "wouldCreate", Json.map("objects", 0, "commits", 0),
                        "refsWouldMove", List.of(),
                        "refsWouldDelete", List.of(Json.map("ref", "refs/heads/" + name, "was", tip.toHex())),
                        "databaseRecords", List.of(),
                        "authorization", Json.map("decision", "ALLOW", "reason", "local repository"),
                        "finalState", Json.map("branch", name, "exists", true),
                        "note", "Deleting a branch removes a name. The commits remain until collection.",
                        "mutated", false);
            }

            context.confirm("Delete branch " + name + " (was " + tip.toHex().substring(0, 12) + ")?");
            workspace.repository().branches().deleteBranch(name);
            context.movedRef("refs/heads/" + name);
            return Json.map("name", name, "was", tip.toHex(), "deleted", true, "mutated", true);
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            if (Boolean.FALSE.equals(data.get("mutated"))) {
                context.out().line("Would delete refs/heads/"
                        + ((Map<?, ?>) ((List<?>) data.get("refsWouldDelete")).get(0)).get("ref")
                        + " — nothing was written.");
                return;
            }
            context.out().line("Deleted branch " + data.get("name"));
        }
    }

    public static final class BranchRename implements Command {

        @Override
        public String name() {
            return "branch rename";
        }

        @Override
        public String summary() {
            return "Rename a branch";
        }

        @Override
        public String usage() {
            return "gitforge branch rename <from> <to>";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public Object run(Context context, List<String> args) {
            if (args.size() != 2) {
                throw CliException.usage(usage());
            }
            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            String from = args.get(0);
            String to = args.get(1);

            ObjectId tip = workspace.repository().branches().getBranch(from)
                    .orElseThrow(() -> CliException.notFound("No such branch: " + from));
            if (workspace.repository().branches().branchExists(to)) {
                throw CliException.conflict("A branch called " + to + " already exists");
            }

            if (context.options().dryRun() || context.options().preview()) {
                return Json.map(
                        "operation", "branch rename",
                        "wouldCreate", Json.map("objects", 0, "commits", 0),
                        "refsWouldMove", List.of(Json.map(
                                "ref", "refs/heads/" + to, "from", null, "to", tip.toHex(),
                                "fastForward", true)),
                        "refsWouldDelete", List.of(Json.map("ref", "refs/heads/" + from, "was", tip.toHex())),
                        "databaseRecords", List.of(),
                        "authorization", Json.map("decision", "ALLOW", "reason", "local repository"),
                        "finalState", Json.map("branch", to, "commit", tip.toHex()),
                        "mutated", false);
            }

            // Create before delete: if the second step fails the branch still
            // exists under one of the two names, which is recoverable. The other
            // order can lose the name entirely.
            var branches = workspace.repository().branches();
            boolean wasCurrent = branches.currentBranch().map(from::equals).orElse(false);
            branches.createBranch(to, tip);
            if (wasCurrent) {
                workspace.repository().refs().setHead(com.gitforge.vcs.ref.Head.onBranch(to));
            }
            branches.deleteBranch(from);
            context.movedRef("refs/heads/" + to);
            context.movedRef("refs/heads/" + from);
            return Json.map("from", from, "to", to, "commit", tip.toHex(),
                    "headFollowed", wasCurrent, "mutated", true);
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line("Renamed " + data.get("from") + " to " + data.get("to"));
        }
    }

    public static final class BranchCompare implements Command {

        @Override
        public String name() {
            return "branch compare";
        }

        @Override
        public String summary() {
            return "Show how far every branch is from HEAD";
        }

        @Override
        public String usage() {
            return "gitforge branch compare";
        }

        @Override
        public Object run(Context context, List<String> args) {
            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            var repository = workspace.repository();
            CommitGraph graph = new CommitGraph(repository.objects());
            List<BranchDivergence.Branch> all = new BranchDivergence(
                    repository.refs(), repository.branches(), graph).againstHead();

            List<Map<String, Object>> rows = new ArrayList<>();
            for (BranchDivergence.Branch branch : all) {
                rows.add(Json.map(
                        "name", branch.name(),
                        "tip", branch.tip().toHex(),
                        "ahead", branch.ahead(),
                        "behind", branch.behind(),
                        "current", branch.current(),
                        "related", branch.related()));
            }
            return Json.map(
                    "base", repository.refs().resolveHead().map(ObjectId::toHex).orElse(null),
                    "count", rows.size(),
                    "branches", rows);
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            for (Object row : (List<?>) data.get("branches")) {
                Map<?, ?> branch = (Map<?, ?>) row;
                context.out().line(String.format("  %-24s %s ahead, %s behind%s",
                        branch.get("name"), branch.get("ahead"), branch.get("behind"),
                        Boolean.TRUE.equals(branch.get("related")) ? "" : "  (unrelated history)"));
            }
        }
    }

    // ---------------------------------------------------------------- switch

    public static final class Switch implements Command {

        @Override
        public String name() {
            return "switch";
        }

        @Override
        public String summary() {
            return "Point HEAD at another branch and update the working tree";
        }

        @Override
        public String usage() {
            return "gitforge switch <branch>";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public Object run(Context context, List<String> args) {
            if (args.size() != 1) {
                throw CliException.usage(usage());
            }
            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            String branch = args.get(0);
            ObjectId target = workspace.repository().branches().getBranch(branch)
                    .orElseThrow(() -> CliException.notFound("No such branch: " + branch));
            String from = workspace.repository().branches().currentBranch().orElse(null);

            if (context.options().dryRun() || context.options().preview()) {
                return Json.map(
                        "operation", "switch",
                        "wouldCreate", Json.map("objects", 0, "commits", 0),
                        "refsWouldMove", List.of(Json.map("ref", "HEAD", "from", from, "to", branch,
                                "fastForward", true)),
                        "refsWouldDelete", List.of(),
                        "databaseRecords", List.of(),
                        "authorization", Json.map("decision", "ALLOW", "reason", "local repository"),
                        "finalState", Json.map("branch", branch, "commit", target.toHex()),
                        "mutated", false);
            }

            new com.gitforge.vcs.worktree.CheckoutService(
                    workspace.repository().refs(),
                    workspace.repository().branches(),
                    workspace.repository().objects(),
                    workspace.workingTree(),
                    workspace.workTreeState())
                    .checkoutBranch(branch);
            context.movedRef("HEAD");
            return Json.map("from", from, "branch", branch, "commit", target.toHex(), "mutated", true);
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line("Switched to branch " + data.get("branch"));
        }
    }

    // ------------------------------------------------------------------ tags

    public static final class TagList implements Command {

        @Override
        public String name() {
            return "tag list";
        }

        @Override
        public String summary() {
            return "List tags, annotated and lightweight";
        }

        @Override
        public String usage() {
            return "gitforge tag list";
        }

        @Override
        public Object run(Context context, List<String> args) {
            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            TagService tags = workspace.repository().tags();

            List<Map<String, Object>> rows = new ArrayList<>();
            int annotated = 0;
            for (String tag : tags.listTags()) {
                Optional<Tag> annotation = tags.annotationOf(tag);
                if (annotation.isPresent()) {
                    annotated++;
                }
                rows.add(Json.map(
                        "name", tag,
                        "annotated", annotation.isPresent(),
                        "target", tags.getTag(tag).map(ObjectId::toHex).orElse(null),
                        "commit", tags.peel(tag).map(ObjectId::toHex).orElse(null),
                        "taggedAt", annotation.map(t -> Json.time(t.tagger().timestamp())).orElse(null)));
            }
            return Json.map(
                    "count", rows.size(),
                    "annotated", annotated,
                    "lightweight", rows.size() - annotated,
                    "tags", rows);
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            for (Object row : (List<?>) data.get("tags")) {
                Map<?, ?> tag = (Map<?, ?>) row;
                context.out().line(String.format("  %-24s %-12s %s",
                        tag.get("name"),
                        Boolean.TRUE.equals(tag.get("annotated")) ? "annotated" : "lightweight",
                        String.valueOf(tag.get("commit")).substring(0, 12)));
            }
        }
    }

    public static final class TagShow implements Command {

        @Override
        public String name() {
            return "tag show";
        }

        @Override
        public String summary() {
            return "Show one tag, including its message when annotated";
        }

        @Override
        public String usage() {
            return "gitforge tag show <name>";
        }

        @Override
        public Object run(Context context, List<String> args) {
            if (args.size() != 1) {
                throw CliException.usage(usage());
            }
            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            TagService tags = workspace.repository().tags();
            String name = args.get(0);
            ObjectId target = tags.getTag(name)
                    .orElseThrow(() -> CliException.notFound("No such tag: " + name));
            Optional<Tag> annotation = tags.annotationOf(name);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("name", name);
            data.put("annotated", annotation.isPresent());
            data.put("target", target.toHex());
            data.put("commit", tags.peel(name).map(ObjectId::toHex).orElse(null));
            annotation.ifPresent(tag -> {
                data.put("tagger", tag.tagger().name());
                data.put("email", tag.tagger().email());
                data.put("taggedAt", Json.time(tag.tagger().timestamp()));
                data.put("message", tag.message().strip());
            });
            return data;
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line("tag " + data.get("name"));
            context.out().line("commit " + data.get("commit"));
            if (Boolean.TRUE.equals(data.get("annotated"))) {
                context.out().line("Tagger: " + data.get("tagger") + " <" + data.get("email") + ">");
                context.out().line("Date:   " + data.get("taggedAt"));
                context.out().line("");
                context.out().line("    " + data.get("message"));
            }
        }
    }

    public static final class TagCreate implements Command {

        @Override
        public String name() {
            return "tag create";
        }

        @Override
        public String summary() {
            return "Create a tag; annotated when given a message";
        }

        @Override
        public String usage() {
            return "gitforge tag create <name> [revision] [-m <message>]";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public Object run(Context context, List<String> args) {
            String name = null;
            String revision = "HEAD";
            String message = null;
            for (int i = 0; i < args.size(); i++) {
                String arg = args.get(i);
                if (arg.equals("-m") || arg.equals("--message")) {
                    if (i + 1 >= args.size()) {
                        throw CliException.usage("-m needs a message");
                    }
                    message = args.get(++i);
                } else if (name == null) {
                    name = arg;
                } else {
                    revision = arg;
                }
            }
            if (name == null) {
                throw CliException.usage(usage());
            }

            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            TagService tags = workspace.repository().tags();
            if (tags.tagExists(name)) {
                // Tags are immutable by design: there is no move operation at any
                // layer, and inventing one here would defeat that decision.
                throw CliException.conflict(
                        "A tag called " + name + " already exists. Tags are immutable; "
                                + "delete it first if you really mean to move it.");
            }
            String at = revision;
            ObjectId target = workspace.repository().reader().resolve(at)
                    .orElseThrow(() -> CliException.notFound("No such revision: " + at));

            if (context.options().dryRun() || context.options().preview()) {
                return Json.map(
                        "operation", "tag create",
                        "wouldCreate", Json.map("objects", message == null ? 0 : 1, "commits", 0),
                        "refsWouldMove", List.of(Json.map(
                                "ref", "refs/tags/" + name, "from", null, "to", target.toHex(),
                                "fastForward", true)),
                        "refsWouldDelete", List.of(),
                        "databaseRecords", List.of(),
                        "authorization", Json.map("decision", "ALLOW", "reason", "local repository"),
                        "annotated", message != null,
                        "finalState", Json.map("tag", name, "commit", target.toHex()),
                        "mutated", false);
            }

            boolean annotated = message != null;
            if (annotated) {
                tags.createAnnotated(name, target, LocalCommands.author(context), message);
            } else {
                tags.createLightweight(name, target);
            }
            context.movedRef("refs/tags/" + name);
            return Json.map(
                    "name", name,
                    "annotated", annotated,
                    "commit", target.toHex(),
                    "mutated", true);
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            if (Boolean.FALSE.equals(data.get("mutated"))) {
                context.out().line("Would create tag " + ((Map<?, ?>) data.get("finalState")).get("tag")
                        + " — nothing was written.");
                return;
            }
            context.out().line("Created "
                    + (Boolean.TRUE.equals(data.get("annotated")) ? "annotated" : "lightweight")
                    + " tag " + data.get("name"));
        }
    }

    public static final class TagDelete implements Command {

        @Override
        public String name() {
            return "tag delete";
        }

        @Override
        public String summary() {
            return "Delete a tag (the commit it named is not removed)";
        }

        @Override
        public String usage() {
            return "gitforge tag delete <name> [--yes]";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public Object run(Context context, List<String> args) {
            List<String> names = args.stream().filter(arg -> !arg.startsWith("--")).toList();
            if (names.size() != 1) {
                throw CliException.usage(usage());
            }
            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            TagService tags = workspace.repository().tags();
            String name = names.get(0);
            ObjectId target = tags.getTag(name)
                    .orElseThrow(() -> CliException.notFound("No such tag: " + name));

            if (context.options().dryRun() || context.options().preview()) {
                return Json.map(
                        "operation", "tag delete",
                        "wouldCreate", Json.map("objects", 0, "commits", 0),
                        "refsWouldMove", List.of(),
                        "refsWouldDelete", List.of(Json.map("ref", "refs/tags/" + name, "was", target.toHex())),
                        "databaseRecords", List.of(),
                        "authorization", Json.map("decision", "ALLOW", "reason", "local repository"),
                        "note", "Deleting a tag removes a name. The commit remains until collection.",
                        "finalState", Json.map("tag", name, "exists", true),
                        "mutated", false);
            }

            context.confirm("Delete tag " + name + "?");
            boolean deleted = tags.deleteTag(name);
            context.movedRef("refs/tags/" + name);
            return Json.map("name", name, "was", target.toHex(), "deleted", deleted, "mutated", deleted);
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line(Boolean.FALSE.equals(data.get("mutated"))
                    ? "Would delete tag — nothing was written."
                    : "Deleted tag " + data.get("name"));
        }
    }

    public static final class TagPeel implements Command {

        @Override
        public String name() {
            return "tag peel";
        }

        @Override
        public String summary() {
            return "Follow a tag to the commit it ultimately names";
        }

        @Override
        public String usage() {
            return "gitforge tag peel <name>";
        }

        @Override
        public Object run(Context context, List<String> args) {
            if (args.size() != 1) {
                throw CliException.usage(usage());
            }
            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            TagService tags = workspace.repository().tags();
            String name = args.get(0);
            ObjectId target = tags.getTag(name)
                    .orElseThrow(() -> CliException.notFound("No such tag: " + name));
            ObjectId commit = tags.peel(name)
                    .orElseThrow(() -> CliException.notFound(
                            "Tag " + name + " does not lead to a commit"));

            return Json.map(
                    "name", name,
                    "target", target.toHex(),
                    "commit", commit.toHex(),
                    "steps", tags.chainOf(name).size());
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line(data.get("name") + " -> " + data.get("commit")
                    + " (" + data.get("steps") + " step(s))");
        }
    }

    public static final class TagChain implements Command {

        @Override
        public String name() {
            return "tag chain";
        }

        @Override
        public String summary() {
            return "Show every object between a tag and its commit";
        }

        @Override
        public String usage() {
            return "gitforge tag chain <name>";
        }

        @Override
        public Object run(Context context, List<String> args) {
            if (args.size() != 1) {
                throw CliException.usage(usage());
            }
            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            TagService tags = workspace.repository().tags();
            String name = args.get(0);
            if (!tags.tagExists(name)) {
                throw CliException.notFound("No such tag: " + name);
            }
            List<String> chain = tags.chainOf(name).stream().map(ObjectId::toHex).toList();
            return Json.map(
                    "name", name,
                    "length", chain.size(),
                    "maxDepth", TagService.MAX_PEEL_DEPTH,
                    "chain", chain);
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line(data.get("name") + ":");
            for (Object step : (List<?>) data.get("chain")) {
                context.out().line("  " + step);
            }
        }
    }
}
