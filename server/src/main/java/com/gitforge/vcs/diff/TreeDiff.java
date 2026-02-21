package com.gitforge.vcs.diff;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The complete set of file-level differences between two trees, sorted by path.
 *
 * @param changes every difference, ordered by path so the result is stable
 */
public record TreeDiff(List<TreeChange> changes) {

    public TreeDiff {
        changes = List.copyOf(changes);
    }

    public static TreeDiff empty() {
        return new TreeDiff(List.of());
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }

    public int size() {
        return changes.size();
    }

    public List<TreeChange.Added> added() {
        return changes.stream().filter(TreeChange.Added.class::isInstance)
                .map(TreeChange.Added.class::cast).toList();
    }

    public List<TreeChange.Deleted> deleted() {
        return changes.stream().filter(TreeChange.Deleted.class::isInstance)
                .map(TreeChange.Deleted.class::cast).toList();
    }

    public List<TreeChange.Modified> modified() {
        return changes.stream().filter(TreeChange.Modified.class::isInstance)
                .map(TreeChange.Modified.class::cast).toList();
    }

    /** Every path touched, in sorted order. */
    public Set<String> paths() {
        return changes.stream().map(TreeChange::path)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
