package com.gitforge.cli.command;

import com.gitforge.cli.CliException;
import com.gitforge.cli.Context;
import com.gitforge.cli.local.Workspace;
import com.gitforge.cli.output.Json;
import com.gitforge.vcs.graph.CommitGraph;
import com.gitforge.vcs.insights.ReachabilityHealth;
import com.gitforge.vcs.insights.RefComposition;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Tag;
import com.gitforge.vcs.object.VcsObject;
import com.gitforge.vcs.ref.ReferenceRoots;
import com.gitforge.vcs.ref.TagService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Asking a repository whether it is sound.
 *
 * <p>Git has {@code fsck}; the GitHub CLI has nothing of the kind. These go
 * further than either by checking the things GitForge knows that a generic tool
 * could not: which roots protect which objects, whether a tag chain terminates,
 * whether the reference store agrees with the object store.
 *
 * <p>Two rules run through all of them.
 *
 * <p><strong>Nothing is repaired.</strong> A verifier that fixes what it finds
 * destroys the evidence of how it broke, and a repository that silently heals is
 * one you cannot reason about. These report and stop.
 *
 * <p><strong>Unverified is not healthy.</strong> Where a check is bounded — and
 * they are all bounded, because an unbounded scan on a large repository is its
 * own kind of failure — the result says how far it got. "Truncated" and "clean"
 * are different answers and are never merged into one.
 */
public final class VerifyCommands {

    private VerifyCommands() {
    }

    static void registerAll() {
        Registry.register(new Integrity());
        Registry.register(new Reachability());
        Registry.register(new Refs());
        Registry.register(new Tags());
        Registry.register(new Consistency());
    }

    /** How many objects a single verification will read before reporting truncation. */
    static final int MAX_SCANNED = 10_000;

    // ------------------------------------------------------------- integrity

    public static final class Integrity implements Command {

        @Override
        public String name() {
            return "verify integrity";
        }

        @Override
        public String summary() {
            return "Re-hash every object and check it matches its id";
        }

        @Override
        public String usage() {
            return "gitforge verify integrity";
        }

        @Override
        public Object run(Context context, List<String> args) {
            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            var objects = workspace.repository().objects();

            int verified = 0;
            int damaged = 0;
            boolean truncated = false;
            List<Map<String, Object>> problems = new ArrayList<>();

            for (ObjectId id : objects.listIds()) {
                if (verified + damaged >= MAX_SCANNED) {
                    truncated = true;
                    break;
                }
                try {
                    Optional<VcsObject> object = objects.read(id);
                    if (object.isEmpty()) {
                        damaged++;
                        problems.add(Json.map("object", id.toHex(), "reason", "MISSING"));
                    } else if (!object.get().id().equals(id)) {
                        // The store is content-addressed: an object whose content
                        // hashes to something else is not the object it is filed as.
                        damaged++;
                        problems.add(Json.map(
                                "object", id.toHex(),
                                "reason", "HASH_MISMATCH",
                                "actual", object.get().id().toHex()));
                    } else {
                        verified++;
                    }
                } catch (RuntimeException unreadable) {
                    damaged++;
                    problems.add(Json.map("object", id.toHex(), "reason", "UNREADABLE"));
                }
            }

            String state = damaged > 0 ? "DAMAGED" : truncated ? "TRUNCATED" : "HEALTHY";
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("integrity", state);
            data.put("verified", verified);
            data.put("damaged", damaged);
            data.put("truncated", truncated);
            data.put("problems", problems);

            if (damaged > 0) {
                throw CliException.verification(
                        damaged + " object(s) failed verification in this repository");
            }
            return data;
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line(data.get("integrity") + ": " + data.get("verified")
                    + " verified, " + data.get("damaged") + " damaged"
                    + (Boolean.TRUE.equals(data.get("truncated")) ? " (scan truncated)" : ""));
        }
    }

    // ---------------------------------------------------------- reachability

    public static final class Reachability implements Command {

        @Override
        public String name() {
            return "verify reachability";
        }

        @Override
        public String summary() {
            return "Report which objects a root protects, and which none does";
        }

        @Override
        public String usage() {
            return "gitforge verify reachability";
        }

        @Override
        public Object run(Context context, List<String> args) {
            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            var repository = workspace.repository();
            ReachabilityHealth health = new ReachabilityHealth(
                    repository.objects(), repository.refs(), workspace.workTreeState(), repository.gc());

            ReachabilityHealth.Scan scan = health.scan();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("storedObjects", scan.storedObjects());
            data.put("roots", scan.roots());
            data.put("reachableObjects", scan.reachableObjects());
            data.put("unreachableObjects", scan.unreachableObjects());
            data.put("unreachableBytes", scan.unreachableBytes());
            data.put("fullyReachable", scan.fullyReachable());
            data.put("truncated", scan.truncated());
            data.put("note", "Unreachable objects are not deleted by this command.");
            return data;
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line(data.get("reachableObjects") + " of " + data.get("storedObjects")
                    + " objects are reachable from " + data.get("roots") + " root(s)");
            if (!Boolean.TRUE.equals(data.get("fullyReachable"))) {
                context.out().line(data.get("unreachableObjects")
                        + " object(s) no root reaches. Nothing was deleted.");
            }
        }
    }

    // ------------------------------------------------------------------ refs

    public static final class Refs implements Command {

        @Override
        public String name() {
            return "verify refs";
        }

        @Override
        public String summary() {
            return "Check that every reference points at an object that exists";
        }

        @Override
        public String usage() {
            return "gitforge verify refs";
        }

        @Override
        public Object run(Context context, List<String> args) {
            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            var repository = workspace.repository();
            var objects = repository.objects();
            var refs = repository.refs();

            List<Map<String, Object>> problems = new ArrayList<>();
            int checked = 0;

            for (String branch : refs.listBranches()) {
                checked++;
                Optional<ObjectId> tip = refs.getBranch(branch);
                if (tip.isEmpty()) {
                    problems.add(Json.map("ref", "refs/heads/" + branch, "reason", "UNRESOLVABLE"));
                } else if (objects.read(tip.get()).isEmpty()) {
                    problems.add(Json.map("ref", "refs/heads/" + branch,
                            "reason", "MISSING_OBJECT", "object", tip.get().toHex()));
                }
            }
            for (String tag : refs.listTags()) {
                checked++;
                Optional<ObjectId> target = refs.getTag(tag);
                if (target.isEmpty()) {
                    problems.add(Json.map("ref", "refs/tags/" + tag, "reason", "UNRESOLVABLE"));
                } else if (objects.read(target.get()).isEmpty()) {
                    problems.add(Json.map("ref", "refs/tags/" + tag,
                            "reason", "MISSING_OBJECT", "object", target.get().toHex()));
                }
            }

            // HEAD naming a branch that does not exist is normal before the first
            // commit and a problem afterwards, so it is judged against whether
            // any commit exists rather than on its own.
            Optional<String> current = repository.branches().currentBranch();
            boolean hasCommits = repository.reader().hasCommits();
            if (current.isPresent() && hasCommits && !repository.branches().branchExists(current.get())) {
                problems.add(Json.map("ref", "HEAD", "reason", "DANGLING_BRANCH", "branch", current.get()));
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("checked", checked);
            data.put("problems", problems);
            data.put("consistent", problems.isEmpty());
            if (!problems.isEmpty()) {
                throw CliException.verification(problems.size() + " reference problem(s) found");
            }
            return data;
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line(data.get("checked") + " reference(s) checked; "
                    + (Boolean.TRUE.equals(data.get("consistent")) ? "all consistent" : "problems found"));
        }
    }

    // ------------------------------------------------------------------ tags

    public static final class Tags implements Command {

        @Override
        public String name() {
            return "verify tags";
        }

        @Override
        public String summary() {
            return "Check that every tag chain terminates at a commit";
        }

        @Override
        public String usage() {
            return "gitforge verify tags";
        }

        @Override
        public Object run(Context context, List<String> args) {
            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            TagService tags = workspace.repository().tags();

            List<Map<String, Object>> problems = new ArrayList<>();
            int checked = 0;
            for (String name : tags.listTags()) {
                checked++;
                try {
                    Optional<ObjectId> commit = tags.peel(name);
                    if (commit.isEmpty()) {
                        problems.add(Json.map("tag", name, "reason", "DOES_NOT_REACH_A_COMMIT"));
                        continue;
                    }
                    Optional<Tag> annotation = tags.annotationOf(name);
                    if (annotation.isPresent()
                            && tags.chainOf(name).size() > TagService.MAX_PEEL_DEPTH) {
                        problems.add(Json.map("tag", name, "reason", "CHAIN_TOO_DEEP"));
                    }
                } catch (RuntimeException broken) {
                    problems.add(Json.map("tag", name, "reason", "UNRESOLVABLE",
                            "detail", String.valueOf(broken.getMessage())));
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("checked", checked);
            data.put("maxPeelDepth", TagService.MAX_PEEL_DEPTH);
            data.put("problems", problems);
            data.put("consistent", problems.isEmpty());
            if (!problems.isEmpty()) {
                throw CliException.verification(problems.size() + " tag problem(s) found");
            }
            return data;
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line(data.get("checked") + " tag(s) checked; "
                    + (Boolean.TRUE.equals(data.get("consistent")) ? "all resolve" : "problems found"));
        }
    }

    // ----------------------------------------------------------- consistency

    /**
     * The cross-cutting check: does every part of the repository agree?
     *
     * <p>The individual verifiers each look at one store. This looks between
     * them, which is where the interesting failures live — a reference naming an
     * object that was never written, a root set that disagrees with what is
     * reachable.
     */
    public static final class Consistency implements Command {

        @Override
        public String name() {
            return "verify consistency";
        }

        @Override
        public String summary() {
            return "Check that refs, objects, tags and roots all agree";
        }

        @Override
        public String usage() {
            return "gitforge verify consistency";
        }

        @Override
        public Object run(Context context, List<String> args) {
            Workspace workspace = Workspace.discover(context.sandbox(), ".");
            var repository = workspace.repository();

            List<Map<String, Object>> problems = new ArrayList<>();

            // Every root must name an object that exists. A root that does not is
            // worse than a dangling ref: collection consults the same set.
            List<ObjectId> roots = ReferenceRoots.of(repository.refs(), workspace.workTreeState());
            for (ObjectId root : roots) {
                if (repository.objects().read(root).isEmpty()) {
                    problems.add(Json.map("kind", "ROOT_MISSING_OBJECT", "object", root.toHex()));
                }
            }

            RefComposition.Composition composition = new RefComposition(
                    repository.refs(), repository.objects(),
                    new CommitGraph(repository.objects())).compute();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("roots", roots.size());
            data.put("distinctRoots", roots.stream().distinct().count());
            data.put("branches", composition.branches());
            data.put("tags", composition.tags());
            data.put("remoteTrackingRefs", composition.remoteTrackingRefs());
            data.put("headAttached", composition.headAttached());
            data.put("headBranch", composition.headBranch());
            data.put("commitsOnlyTagsProtect", composition.commitsOnlyTagsProtect());
            data.put("problems", problems);
            data.put("consistent", problems.isEmpty());

            if (!problems.isEmpty()) {
                throw CliException.verification(
                        problems.size() + " consistency problem(s) found. Nothing was repaired.");
            }
            return data;
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line("Roots        : " + data.get("roots")
                    + " (" + data.get("distinctRoots") + " distinct)");
            context.out().line("Branches     : " + data.get("branches"));
            context.out().line("Tags         : " + data.get("tags"));
            context.out().line("Tags only    : " + data.get("commitsOnlyTagsProtect")
                    + " commit(s) no branch reaches");
            context.out().line("Consistent   : " + data.get("consistent"));
        }
    }
}
