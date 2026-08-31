package com.gitforge.vcsapi;

import com.gitforge.common.error.BadRequestException;
import com.gitforge.vcs.object.ObjectId;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Where a page of history left off.
 *
 * <p>Three things, and deliberately no more: the commit the walk started from,
 * the path it was filtered by, and how far into that walk the previous page
 * ended. Everything else about the next page is derivable, and a cursor that
 * carried more would be a cursor that could contradict its own request.
 *
 * <p><strong>The start commit is recorded, not the ref.</strong> A branch that
 * moves between two pages would otherwise silently change what is being paged
 * through, and the client would receive commits that skip or repeat with nothing
 * to indicate it. Pinning the commit makes a paged walk a walk over one
 * snapshot, which is the only version of the promise that is actually keepable.
 *
 * <p><strong>It is not signed, and must not be.</strong> A signature would make
 * this a capability — something that grants what the bearer could not otherwise
 * reach. It grants nothing: every page is authorised from scratch through
 * {@code VcsRepositoryProvider.forRead}, so a forged cursor reaches exactly the
 * repositories its sender could already read. Signing it would suggest otherwise
 * to whoever maintains this next, which is the more expensive mistake.
 *
 * <p>Opaque to clients all the same. The encoding is an implementation detail,
 * and the moment a client parses one, it becomes a contract.
 */
record HistoryCursor(ObjectId start, String path, int offset) {

    /**
     * Prefix and version marker.
     *
     * <p>Present so that a cursor issued by an older server can be recognised and
     * refused rather than misread. A format change without one would decode
     * plausibly and page through the wrong history.
     */
    private static final String VERSION = "v1";

    private static final String SEPARATOR = ":";

    /** Version, start, offset, then the path — last, so its own colons are safe. */
    private static final int FIELD_COUNT = 4;

    HistoryCursor {
        if (start == null) {
            throw new IllegalArgumentException("Cursor start commit must not be null");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("Cursor offset must not be negative");
        }
        path = path == null ? "" : path;
    }

    /** The cursor a client sends back to continue from here. */
    String encode() {
        String payload = String.join(SEPARATOR, VERSION, start.toHex(), Integer.toString(offset), path);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reads a cursor a client sent back.
     *
     * <p>Every failure is the same failure to the caller — a cursor that cannot
     * be used — and all of them are the client's to fix by starting the walk
     * again. What must never happen is any of them being treated as "no cursor":
     * that silently restarts the walk at the top, and a client paging in a loop
     * would never reach the end or notice why.
     *
     * @throws BadRequestException if the text is not a cursor this server issued
     */
    static HistoryCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw refuse("A cursor must not be empty");
        }

        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(encoded.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw refuse("Not a valid cursor");
        }

        String[] fields = payload.split(SEPARATOR, FIELD_COUNT);
        if (fields.length != FIELD_COUNT) {
            throw refuse("Not a valid cursor");
        }
        if (!VERSION.equals(fields[0])) {
            throw refuse("Not a valid cursor: unrecognised format");
        }

        ObjectId start;
        try {
            start = ObjectId.fromHex(fields[1]);
        } catch (IllegalArgumentException ex) {
            throw refuse("Not a valid cursor: malformed start commit");
        }

        int offset;
        try {
            offset = Integer.parseInt(fields[2]);
        } catch (NumberFormatException ex) {
            throw refuse("Not a valid cursor: malformed position");
        }
        if (offset < 0) {
            throw refuse("Not a valid cursor: negative position");
        }

        return new HistoryCursor(start, fields[3], offset);
    }

    /** The cursor for the page after this one, {@code taken} commits further on. */
    HistoryCursor advancedBy(int taken) {
        return new HistoryCursor(start, path, offset + taken);
    }

    /**
     * Refuses a cursor that does not belong to the request carrying it.
     *
     * <p>A cursor names the walk it came from. Continuing it against a different
     * revision or a different path filter would return commits that answer
     * neither question — the client would see one branch's history under
     * another's name, with the mismatch invisible in the response.
     */
    void requireMatches(ObjectId requestedStart, String requestedPath) {
        if (!start.equals(requestedStart)) {
            throw refuse("This cursor belongs to a different revision. Start the walk again.");
        }
        if (!path.equals(requestedPath == null ? "" : requestedPath)) {
            throw refuse("This cursor belongs to a different path filter. Start the walk again.");
        }
    }

    private static BadRequestException refuse(String message) {
        return new BadRequestException(message);
    }
}
