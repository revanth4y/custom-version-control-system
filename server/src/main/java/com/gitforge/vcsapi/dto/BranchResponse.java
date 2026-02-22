package com.gitforge.vcsapi.dto;

/**
 * A branch and the commit it points at.
 *
 * @param head whether HEAD currently names this branch
 */
public record BranchResponse(String name, String commit, boolean head) {
}
