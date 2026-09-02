package com.gitforge.vcsapi.dto;

import java.util.List;

/**
 * Which of the offered ids this repository does not hold.
 *
 * <p>Asked before a push so it carries what is needed rather than everything
 * reachable. The answer names only ids that were offered — a repository volunteer-
 * ing ids nobody mentioned would be answering a different question.
 *
 * @param missing the subset the caller should send
 */
public record MissingObjectsResponse(List<String> missing) {
}
