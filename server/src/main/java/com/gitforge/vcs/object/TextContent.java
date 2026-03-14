package com.gitforge.vcs.object;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The single definition of what counts as text.
 *
 * <p>Both the diff engine and the content API need to answer "can these bytes be
 * treated as text?", and they must answer it the same way: a file the API
 * returns as base64 must also be a file the differ declines to line-diff.
 * Keeping the rule in one place stops the two from drifting apart.
 *
 * <p>Content is text only if it holds no NUL byte and decodes strictly as UTF-8.
 * The decoder is set to report malformed input rather than substitute
 * replacement characters, which is what prevents a binary file from being
 * silently mangled into lossy text.
 */
public final class TextContent {

    private TextContent() {
    }

    public static boolean isBinary(byte[] content) {
        return asText(content).isEmpty();
    }

    /** The text form of {@code content}, or empty if it is not valid UTF-8 text. */
    public static Optional<String> asText(byte[] content) {
        if (content == null) {
            return Optional.empty();
        }
        for (byte value : content) {
            if (value == 0) {
                return Optional.empty();
            }
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return Optional.of(decoder.decode(ByteBuffer.wrap(content)).toString());
        } catch (CharacterCodingException ex) {
            return Optional.empty();
        }
    }

    /**
     * Splits text into lines for diffing.
     *
     * <p>A trailing newline does not create a final empty line: a file ending
     * "a\n" has one line, not two. Without this every file would appear to differ
     * from itself in its last line.
     */
    public static String[] lines(String text) {
        if (text.isEmpty()) {
            return new String[0];
        }
        String body = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        return body.split("\n", -1);
    }
}
