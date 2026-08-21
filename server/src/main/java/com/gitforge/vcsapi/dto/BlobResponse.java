package com.gitforge.vcsapi.dto;

/**
 * A file's contents.
 *
 * <p>JSON cannot carry arbitrary bytes, so content that is not valid text is
 * base64-encoded and flagged. {@code encoding} states which form was used, so a
 * client never has to guess and binary files round-trip exactly.
 *
 * @param binary true when the content could not be represented as text
 * @param encoding {@code utf-8} or {@code base64}
 */
public record BlobResponse(
        String path,
        String id,
        String mode,
        int size,
        boolean binary,
        String encoding,
        String content) {
}
