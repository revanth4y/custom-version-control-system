/** Ordering choices offered on the branch list. */
export const SORT_MODES = [
  { key: "activity", label: "Recently updated" },
  { key: "name", label: "Name" },
];

/**
 * The commit subject: the first line of the message.
 *
 * The engine normalises a message to exactly one trailing newline, so the raw
 * value almost always ends in one. Everything after the first line is the body,
 * which a one-line-per-branch listing has no room for.
 */
export function subjectOf(message) {
  if (!message) return "";
  const [first] = message.split("\n");
  return first.trim();
}

/** Case-insensitive substring match on the branch name. */
export function filterBranches(branches, query) {
  const needle = (query ?? "").trim().toLowerCase();
  if (!needle) return branches ?? [];
  return (branches ?? []).filter((branch) => branch.name.toLowerCase().includes(needle));
}

/**
 * Orders branches for display.
 *
 * Sorting is stable and total: ties break on name so the list never reorders
 * between renders. A branch whose tip could not be read sorts last under
 * "recently updated" rather than being treated as infinitely old, which would
 * put a broken reference at the top of the list.
 */
export function sortBranches(branches, mode) {
  const copy = [...(branches ?? [])];

  if (mode === "name") {
    return copy.sort((a, b) => a.name.localeCompare(b.name));
  }
  return copy.sort((a, b) => {
    const left = timeOf(a);
    const right = timeOf(b);
    if (left !== right) return right - left;
    return a.name.localeCompare(b.name);
  });
}

function timeOf(branch) {
  const stamp = branch.tip?.timestamp;
  if (!stamp) return Number.NEGATIVE_INFINITY;
  const value = Date.parse(stamp);
  return Number.isNaN(value) ? Number.NEGATIVE_INFINITY : value;
}

/**
 * Puts the branch HEAD names first, keeping the rest in their given order.
 *
 * Used by the selector, where the point is to get back to the current branch
 * quickly. The branch list page deliberately does not do this: it offers an
 * explicit sort, and silently pinning a row would contradict it.
 */
export function currentFirst(branches, headBranch) {
  if (!headBranch) return branches ?? [];
  const list = branches ?? [];
  return [...list.filter((b) => b.name === headBranch), ...list.filter((b) => b.name !== headBranch)];
}
