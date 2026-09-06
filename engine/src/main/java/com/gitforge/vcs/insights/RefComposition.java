package com.gitforge.vcs.insights;

import com.gitforge.vcs.graph.CommitGraph;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Tag;
import com.gitforge.vcs.object.CorruptObjectException;
import com.gitforge.vcs.object.VcsObject;
import com.gitforge.vcs.ref.Head;
import com.gitforge.vcs.ref.RefStore;
import com.gitforge.vcs.ref.TagService;
import com.gitforge.vcs.storage.ObjectStore;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * What kinds of reference a repository holds, and what only a tag is keeping.
 *
 * <p>Counts come from the reference store, so a deleted ref is gone the moment it
 * is deleted — there is nothing stored that could keep reporting it.
 *
 * <p><strong>"Commits protected only by a tag", defined precisely.</strong> The
 * commits reachable from the tags, minus the commits reachable from every other
 * root — branches, HEAD and remote-tracking refs. It is a set difference, so a
 * commit reachable from two tags is counted once, and a commit reachable from a
 * tag <em>and</em> a branch is not counted at all.
 *
 * <p>The figure answers a question a tag exists to make askable: how much of this
 * history would become collectible if the tags went away. On a repository where
 * every tag sits on the main line it is zero, and that zero is informative rather
 * than a gap.
 */
public final class RefComposition {

    private final RefStore refs;
    private final ObjectStore objects;
    private final CommitGraph graph;

    public RefComposition(RefStore refs, ObjectStore objects, CommitGraph graph) {
        if (refs == null || objects == null || graph == null) {
            throw new IllegalArgumentException("Ref composition needs references, a store and a graph");
        }
        this.refs = refs;
        this.objects = objects;
        this.graph = graph;
    }

    /**
     * @param headAttached whether HEAD names a branch rather than a commit directly
     * @param commitsOnlyTagsProtect commits no branch, HEAD or tracking ref reaches
     */
    public record Composition(
            int branches,
            int tags,
            int remoteTrackingRefs,
            int remotes,
            boolean headAttached,
            String headBranch,
            int commitsOnlyTagsProtect) {

        /** Every named reference, whatever its kind. */
        public int total() {
            return branches + tags + remoteTrackingRefs;
        }
    }

    public Composition compute() {
        Head head = refs.readHead();
        boolean attached = head instanceof Head.OnBranch;
        String headBranch = attached ? ((Head.OnBranch) head).branch() : null;

        var remoteRefs = refs.listRemoteRefs();
        int distinctRemotes = (int) remoteRefs.stream().map(ref -> ref.remote()).distinct().count();

        return new Composition(
                refs.listBranches().size(),
                refs.listTags().size(),
                remoteRefs.size(),
                distinctRemotes,
                attached,
                headBranch,
                commitsOnlyTagsProtect().size());
    }

    /**
     * The commits nothing but a tag reaches.
     *
     * <p>Deliberately a set rather than a count internally, so the arithmetic is
     * a difference of sets and double counting is impossible by construction
     * rather than by care.
     */
    public Set<ObjectId> commitsOnlyTagsProtect() {
        // Each reference is still visited in the same order as before, and each
        // still contributes its ancestors in breadth-first order. What changed
        // is that a walk no longer replays the part of history an earlier
        // reference already accounted for: it stops as soon as it reaches
        // something the accumulating set holds. Both sets stay ancestor-closed
        // while this runs, so stopping cannot miss a commit, and a commit that
        // would have been added and then skipped as a duplicate is simply never
        // reached twice. Same set, same order, one traversal of the graph
        // instead of one per reference.
        Set<ObjectId> withoutTags = new LinkedHashSet<>();

        for (String branch : refs.listBranches()) {
            refs.getBranch(branch)
                    .ifPresent(tip -> graph.collectAncestors(tip, Set.of(), withoutTags));
        }
        refs.resolveHead().ifPresent(tip -> graph.collectAncestors(tip, Set.of(), withoutTags));
        refs.listRemoteRefs()
                .forEach(ref -> graph.collectAncestors(ref.commit(), Set.of(), withoutTags));

        // Tags are walked against that set as a boundary rather than subtracting
        // it afterwards. Anything inside it is destined for removal, and so is
        // everything beneath it, so descending there could only produce commits
        // that the final difference discards.
        Set<ObjectId> fromTags = new LinkedHashSet<>();
        for (String tag : refs.listTags()) {
            refs.getTag(tag)
                    .flatMap(this::peelToCommit)
                    .ifPresent(commit -> graph.collectAncestors(commit, withoutTags, fromTags));
        }
        return fromTags;
    }

    /**
     * A ref target peeled to the commit it names, following tag objects.
     *
     * <p>The same rule statistics use, bounded by the same ceiling, because two
     * different peeling depths would be two different answers to one question.
     */
    private Optional<ObjectId> peelToCommit(ObjectId target) {
        ObjectId current = target;
        for (int depth = 0; depth <= TagService.MAX_PEEL_DEPTH; depth++) {
            Optional<VcsObject> object;
            try {
                object = objects.read(current);
            } catch (CorruptObjectException ex) {
                return Optional.empty();
            }
            if (object.isEmpty()) {
                return Optional.empty();
            }
            if (object.get() instanceof com.gitforge.vcs.object.Commit) {
                return Optional.of(current);
            }
            if (object.get() instanceof Tag tag) {
                current = tag.target();
                continue;
            }
            return Optional.empty();
        }
        return Optional.empty();
    }
}
