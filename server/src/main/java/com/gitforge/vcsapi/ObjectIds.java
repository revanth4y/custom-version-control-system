package com.gitforge.vcsapi;

import com.gitforge.common.error.BadRequestException;
import com.gitforge.common.error.NotFoundException;
import com.gitforge.vcs.object.AmbiguousObjectIdException;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.repository.VcsRepository;

import java.util.List;

/**
 * Turning what a caller wrote in a URL into an object id.
 *
 * <p>Here rather than in two API services because it was in two API services:
 * commit detail and commit diff each parsed the same path variable their own
 * way, which is one copy too many for a rule about identity.
 *
 * <p>Every method assumes the repository has already been loaded through the
 * usual read check. Resolution must never be the thing that decides whether a
 * caller may look — a stranger asking about a private repository has to be told
 * it is missing before an ambiguous prefix could tell them otherwise.
 */
final class ObjectIds {

    private ObjectIds() {
    }

    /**
     * Whether this is a relative expression written against an id.
     *
     * <p>This path variable has only ever named an object, never a branch and
     * never {@code HEAD} — those are a {@code ref} elsewhere, and a name here is
     * still a bad request. Relative expressions follow the same line: an id or
     * an abbreviation may carry a suffix, and a name may not, so
     * {@code <id>~1} resolves for the same reason {@code <id>} does and
     * {@code HEAD~1} is refused for the same reason {@code HEAD} is.
     *
     * <p>Anything without a suffix character is left entirely alone, which is
     * what keeps every existing answer here — including the bad request for text
     * that is not hexadecimal — exactly as it was.
     */
    private static boolean isRelativeToAnId(String text) {
        for (int index = 1; index < text.length(); index++) {
            if (text.charAt(index) == '~' || text.charAt(index) == '^') {
                String base = text.substring(0, index);
                return ObjectId.isValidPrefix(base) || isFullId(base);
            }
        }
        return false;
    }

    private static boolean isFullId(String text) {
        try {
            ObjectId.fromHex(text);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /**
     * Resolves a full id or an unambiguous abbreviation of one.
     *
     * <p>The complete id is tried first and returned without touching the store,
     * which keeps the existing behaviour exactly: a well-formed forty-character
     * id that names nothing is the caller's to discover as a missing commit,
     * not as a malformed one.
     *
     * @throws BadRequestException if the text is neither an id nor long enough
     *     to be an abbreviation
     * @throws NotFoundException if an abbreviation matches no stored object
     * @throws AmbiguousObjectIdException if it matches several
     */
    static ObjectId resolve(VcsRepository repository, String sha) {
        if (sha == null || sha.isBlank()) {
            throw new BadRequestException("A commit id is required");
        }
        String trimmed = sha.trim();

        try {
            return ObjectId.fromHex(trimmed);
        } catch (IllegalArgumentException ex) {
            // Not the whole thing. It may be the start of it.
        }

        if (isRelativeToAnId(trimmed)) {
            return repository.reader().resolve(trimmed)
                    .orElseThrow(() -> new NotFoundException("No such commit: " + trimmed));
        }

        if (!ObjectId.isValidPrefix(trimmed)) {
            throw new BadRequestException(
                    "Not a valid commit id: " + trimmed + ". Use the full 40 characters, or at least "
                            + ObjectId.MIN_PREFIX_LENGTH + " hexadecimal characters of one.");
        }

        List<ObjectId> matches = repository.objects().findByPrefix(trimmed);
        if (matches.isEmpty()) {
            throw new NotFoundException("No such commit: " + trimmed);
        }
        if (matches.size() > 1) {
            throw new AmbiguousObjectIdException(trimmed, matches);
        }
        return matches.getFirst();
    }
}
