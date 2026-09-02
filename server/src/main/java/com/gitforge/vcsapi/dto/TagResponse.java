package com.gitforge.vcsapi.dto;

import java.time.Instant;

/**
 * A tag, and what it ultimately names.
 *
 * <p>Both ids are carried because they answer different questions and a caller
 * usually wants the second. {@code target} is what the ref literally holds — the
 * tag object's id for an annotated tag — while {@code commit} is the end of the
 * chain after peeling. For a lightweight tag they are the same, and saying so
 * explicitly is cheaper than making every caller work it out.
 *
 * @param annotated whether a tag object stands behind the ref
 * @param message the tag object's message, or null for a lightweight tag
 * @param taggerName who wrote the annotation, or null for a lightweight tag
 * @param taggedAt when the annotation was written, or null for a lightweight tag
 * @param tip the commit at the end of the chain, or null when the tag names
 *     something that is not a commit, or one that cannot be read
 */
public record TagResponse(
        String name,
        String target,
        String commit,
        boolean annotated,
        String message,
        String taggerName,
        String taggerEmail,
        Instant taggedAt,
        BranchTipResponse tip) {
}
