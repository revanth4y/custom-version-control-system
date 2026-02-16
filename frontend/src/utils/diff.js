/**
 * Reading the backend's diff responses.
 *
 * No diffing happens here. The engine owns what a diff means - it is a
 * statement about repository content, and two clients computing it separately
 * could disagree - so this only classifies and summarises what it returns.
 */

/**
 * Why a file's diff has no lines to show, or that it has.
 *
 * Three quite different situations all arrive as a file with no hunks and no
 * counts: content that is not text, content too large to diff, and a change
 * that touched only the mode. Telling them apart is the whole point of this
 * function, because "no changes" is the wrong thing to say about all three.
 */
export const FileState = {
  BINARY: "binary",
  TOO_LARGE: "tooLarge",
  MODE_ONLY: "modeOnly",
  UNCHANGED: "unchanged",
  TEXT: "text",
};

export function fileState(file) {
  if (!file) return FileState.UNCHANGED;
  if (file.binary) return FileState.BINARY;
  if (file.tooLarge) return FileState.TOO_LARGE;
  if ((file.hunks?.length ?? 0) > 0) return FileState.TEXT;
  if (modeChange(file)) return FileState.MODE_ONLY;
  return FileState.UNCHANGED;
}

/**
 * The mode change, if the file's mode actually changed.
 *
 * A file being added or deleted has one side null, which is not a mode change -
 * it is the file appearing or disappearing, and the status already says so.
 */
export function modeChange(file) {
  const from = file?.oldMode;
  const to = file?.newMode;
  if (!from || !to || from === to) return null;

  const EXECUTABLE = "100755";
  let label;
  if (to === EXECUTABLE) label = "made executable";
  else if (from === EXECUTABLE) label = "no longer executable";
  else label = `mode changed from ${from} to ${to}`;

  return { from, to, label };
}

/** Counts per status, which the line-level endpoint does not send. */
export function summarise(files) {
  const list = files ?? [];
  const counts = { files: list.length, added: 0, modified: 0, deleted: 0, additions: 0, deletions: 0 };

  for (const file of list) {
    if (file.status === "ADDED") counts.added += 1;
    else if (file.status === "DELETED") counts.deleted += 1;
    else if (file.status === "MODIFIED") counts.modified += 1;
    counts.additions += file.additions ?? 0;
    counts.deletions += file.deletions ?? 0;
  }
  return counts;
}

/**
 * A stable element id for a file's section.
 *
 * Percent-encoded rather than slugified: a path may contain any character, and
 * replacing awkward ones with a dash would let `a/b` and `a-b` collide and
 * scroll to each other's diff. Encoding is reversible, so it cannot.
 */
export function anchorFor(path) {
  return `file-${encodeURIComponent(path ?? "")}`;
}

export function pathFromAnchor(anchor) {
  if (typeof anchor !== "string" || !anchor.startsWith("file-")) return null;
  try {
    return decodeURIComponent(anchor.slice("file-".length));
  } catch {
    // A hand-edited fragment can be invalid percent-encoding; that is a missing
    // file, not a crash.
    return null;
  }
}

/** The directory and filename, so a long path can be de-emphasised in the middle. */
export function splitPath(path) {
  const full = path ?? "";
  const cut = full.lastIndexOf("/");
  return cut === -1
    ? { directory: "", name: full }
    : { directory: full.slice(0, cut + 1), name: full.slice(cut + 1) };
}

/** Human sizes for the two sides of a binary change. */
export const STATUS_LABEL = {
  ADDED: "added",
  DELETED: "deleted",
  MODIFIED: "modified",
};
