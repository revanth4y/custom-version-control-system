package com.gitforge.vcsapi.dto;

/**
 * A branch and the commit it points at.
 *
 * @param head whether HEAD currently names this branch
 * @param tip details of the commit at {@code commit}, or null if the reference
 *     names a commit that cannot be read
 */
public record BranchResponse(String name, String commit, boolean head, BranchTipResponse tip) {
}
