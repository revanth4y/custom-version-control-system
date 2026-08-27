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
