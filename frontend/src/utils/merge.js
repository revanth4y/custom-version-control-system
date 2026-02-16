/**
 * Reading the backend's merge responses.
 *
 * The engine decides what a merge does; nothing here re-decides it. A conflict
 * is a real answer rather than a failure - the engine writes nothing and moves
 * nothing - so the 409 that carries it holds a complete result that must be
 * read, not discarded as an error.
 */

export const Outcome = {
  ALREADY_UP_TO_DATE: "ALREADY_UP_TO_DATE",
  FAST_FORWARDED: "FAST_FORWARDED",
  MERGED: "MERGED",
  CONFLICTED: "CONFLICTED",
};

/**
 * What each conflict kind means, in the engine's own terms.
 *
 * Kept as data so the display cannot drift from the enum: an unknown kind is
 * shown as itself rather than silently mapped to something plausible.
 */
export const CONFLICT_KINDS = {
  CONTENT: {
    label: "Content",
    summary: "Both sides changed the same file differently.",
  },
  ADD_ADD: {
    label: "Add / add",
    summary: "The file did not exist before and both sides created it with different content.",
  },
  MODIFY_DELETE: {
    label: "Modify / delete",
    summary: "One side changed the file while the other removed it.",
  },
  MODE: {
    label: "Mode",
    summary: "The contents agree, but the two sides disagree about the file mode.",
  },
  TYPE: {
    label: "Type",
    summary: "One side has a file where the other has a directory.",
  },
};

export function describeKind(kind) {
  return CONFLICT_KINDS[kind] ?? { label: kind ?? "Unknown", summary: "" };
}

/**
 * Which way round a modify/delete conflict goes.
 *
 * Read straight off the data: an absent side means the path was not there, so
 * whichever side is missing is the one that deleted it.
 */
export function deletedBy(conflict) {
  if (conflict?.kind !== "MODIFY_DELETE") return null;
  if (conflict.ours && !conflict.theirs) return "theirs";
  if (conflict.theirs && !conflict.ours) return "ours";
  return null;
}

/** A conflict where one side is a directory and the other is not. */
export function isTypeMismatch(conflict) {
  const ours = conflict?.ours;
  const theirs = conflict?.theirs;
  if (!ours || !theirs) return false;
  return ours.directory !== theirs.directory;
}

/** Counts per kind, in the order the kinds are declared, for a summary line. */
export function countByKind(conflicts) {
  const counts = new Map();
  for (const conflict of conflicts ?? []) {
    counts.set(conflict.kind, (counts.get(conflict.kind) ?? 0) + 1);
  }
  const known = Object.keys(CONFLICT_KINDS).filter((kind) => counts.has(kind));
  const unknown = [...counts.keys()].filter((kind) => !(kind in CONFLICT_KINDS)).sort();
  return [...known, ...unknown].map((kind) => ({ kind, count: counts.get(kind) }));
}

/**
 * Whether a merge may be attempted, and why not when it may not.
 *
 * Merging a branch into itself is refused here rather than sent: the engine
 * would answer "already up to date", which is true but useless, and the
 * request would be a write nobody meant to make.
 */
export function validateMerge({ target, source, branches }) {
  if (!target || !source) return "Choose a target and a source branch.";
  if (target === source) return "A branch cannot be merged into itself.";

  const names = new Set((branches ?? []).map((branch) => branch.name));
  // Only checked once the list is known; an empty list means "not loaded yet"
  // rather than "no branches exist".
  if (names.size > 0) {
    if (!names.has(target)) return `There is no branch named ${target}.`;
    if (!names.has(source)) return `There is no branch named ${source}.`;
  }
  return null;
}

/**
 * The merge result carried by either a success or a 409.
 *
 * A conflicted merge arrives as a rejected request whose body is the answer.
 * Returning it here keeps that single place, so no caller has to remember that
 * one particular error is not one.
 */
export function resultFrom(error) {
  const body = error?.response?.data;
  if (error?.response?.status === 409 && body?.outcome === Outcome.CONFLICTED) {
    return body;
  }
  return null;
}

/** True when the outcome moved the target branch. */
export function movedTheBranch(outcome) {
  return outcome === Outcome.FAST_FORWARDED || outcome === Outcome.MERGED;
}
