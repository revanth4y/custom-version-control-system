package com.gitforge.vcsapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param name the tag to create
 * @param target what it should point at: a branch name, another tag, {@code HEAD},
 *     or a commit id — anything revision resolution accepts
 * @param message present and non-blank produces an annotated tag; absent or blank
 *     produces a lightweight one. The distinction is the message itself rather
 *     than a separate flag, because an annotated tag with nothing to say and a
 *     lightweight tag are the same thing, and two ways to ask for one thing is
 *     one way too many.
 */
public record CreateTagRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 255) String target,
        @Size(max = 10_000) String message) {
}
