package com.gitforge.vcsapi.dto;

import java.util.List;

/** The contents of one directory at a revision. */
public record DirectoryResponse(String ref, String path, List<TreeEntryResponse> entries) {
}
