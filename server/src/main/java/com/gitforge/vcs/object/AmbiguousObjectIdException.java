package com.gitforge.vcs.object;

import java.util.List;

/**
 * An abbreviated object id names more than one object.
 *
 * <p>Raised rather than resolved, because there is no correct answer to pick.
 * Returning the first match would be a guess, and a guess about identity is the
 * one thing a content-addressed store must never make: the caller would be
 * handed a commit they did not ask for, with nothing to indicate it.
 *
 * <p>The candidates travel with the exception so the boundary can name them.
 * Being told a prefix is ambiguous and not which objects collided leaves the
 * caller to guess in turn.
 */
public class AmbiguousObjectIdException extends RuntimeException {

    /** How many candidates are worth naming before the list stops helping. */
    private static final int NAMED_CANDIDATES = 5;

    /** Enough to distinguish the collisions without printing forty characters each. */
    private static final int CANDIDATE_LENGTH = 12;

    private final String prefix;
    private final List<ObjectId> candidates;

    public AmbiguousObjectIdException(String prefix, List<ObjectId> candidates) {
        super(describe(prefix, candidates));
        this.prefix = prefix;
        this.candidates = List.copyOf(candidates);
    }

    public String prefix() {
        return prefix;
    }

    /** Every colliding id, not only the ones the message names. */
    public List<ObjectId> candidates() {
        return candidates;
    }

    /**
     * The message a caller sees.
     *
     * <p>The full count is always stated, even when the list is cut short: "three
     * of many" and "three" are different situations, and lengthening the prefix
     * is only obviously the fix when you know how much collides.
     */
    private static String describe(String prefix, List<ObjectId> candidates) {
        String named = candidates.stream()
                .limit(NAMED_CANDIDATES)
                .map(id -> id.abbreviate(CANDIDATE_LENGTH))
                .reduce((a, b) -> a + ", " + b)
                .orElse("");

        String suffix = candidates.size() > NAMED_CANDIDATES
                ? ", and " + (candidates.size() - NAMED_CANDIDATES) + " more"
                : "";

        return "Object id prefix '" + prefix + "' is ambiguous: "
                + candidates.size() + " objects match (" + named + suffix + "). "
                + "Use more characters.";
    }
}
