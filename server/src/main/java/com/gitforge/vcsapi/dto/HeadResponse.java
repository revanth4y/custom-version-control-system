package com.gitforge.vcsapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What the repository currently has checked out.
 *
 * @param branch the branch HEAD names, or null when detached
 * @param commit the commit HEAD resolves to, or null before the first commit
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record HeadResponse(String branch, String commit, boolean detached) {
}
