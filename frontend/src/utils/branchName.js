/**
 * Mirrors com.gitforge.vcs.ref.BranchName.
 *
 * A branch name becomes a path under refs/heads on the server, which is why the
 * rules are strict there. Repeating them here does not make the client an
 * authority — the server validates independently and its message is shown
 * verbatim if it disagrees — it only means an obviously bad name is reported
 * while typing instead of after a round trip.
 */

/** Characters that carry meaning in revision syntax, or that break paths. */
const FORBIDDEN = new Set(["~", "^", ":", "?", "*", "[", "]", "\\", '"', "'", "<", ">", "|", " "]);

/** Matches the @Size(max = 255) bound on CreateBranchRequest. */
export const MAX_BRANCH_NAME_LENGTH = 255;

export function validateBranchName(rawName) {
  const name = rawName ?? "";

  if (!name.trim()) return "A branch name is required.";
  if (name.length > MAX_BRANCH_NAME_LENGTH) {
    return `Must be ${MAX_BRANCH_NAME_LENGTH} characters or fewer.`;
  }
  if (name === "HEAD") return "'HEAD' is reserved.";
  if (name.startsWith("/") || name.endsWith("/")) return "Cannot start or end with '/'.";
  if (name.includes("//")) return "Cannot contain an empty segment.";
  // Catches an absolute Windows path such as C:\work before the character scan
  // reports something less specific.
  if (name.length > 1 && name[1] === ":") return "Cannot be an absolute path.";
  if (name.includes("@{")) return "Cannot contain '@{'.";

  for (const character of name) {
    const code = character.codePointAt(0);
    if (code < 0x20 || code === 0x7f) return "Cannot contain control characters.";
    if (FORBIDDEN.has(character)) return `Cannot contain '${character}'.`;
  }

  for (const segment of name.split("/")) {
    const problem = validateSegment(segment);
    if (problem) return problem;
  }
  return null;
}

function validateSegment(segment) {
  if (!segment) return "Cannot contain an empty segment.";
  if (segment === "." || segment === "..") return "Cannot contain '.' or '..' segments.";
  if (segment.startsWith(".")) return "Segments cannot start with '.'.";
  if (segment.startsWith("-")) return "Segments cannot start with '-'.";
  if (segment.endsWith(".lock")) return "Segments cannot end with '.lock'.";
  return null;
}
