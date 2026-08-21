package com.gitforge.vcsapi.dto;

/**
 * A commit together with what it changed.
 *
 * <p>Changes are measured against the first parent. For a merge that means the
 * branch it was merged into, since the second parent's work is by definition
 * already present there.
 */
public record CommitDetailResponse(CommitSummaryResponse commit, DiffResponse changes) {
}
