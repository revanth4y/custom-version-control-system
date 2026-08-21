package com.gitforge.vcsapi.dto;

/** The difference between two revisions. */
public record CompareResponse(String base, String head, DiffResponse changes) {
}
