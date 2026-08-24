/**
 * Reading a directory listing for display.
 *
 * `GET /tree?withLastCommit=true` attaches a `lastCommit` to each entry, but the
 * field is serialised only when present: a path the history walk could not
 * resolve within its window simply has no `lastCommit` at all. Every one of
 * these functions therefore has to answer for an entry that has none, which is
 * a normal state rather than an error.
 */

/** Directories first, then files; the API already orders within each group. */
export const sortEntries = (entries) =>
  [...entries].sort((a, b) => {
    if (a.type !== b.type) return a.type === "dir" ? -1 : 1;
    return 0;
  });

/**
 * The first line of a commit message.
 *
 * Messages arrive with their trailing newline intact — "Commit number 60\n" —
 * and a multi-line message would otherwise drag the row's height around. The
 * subject is what a listing wants; the body belongs on the commit itself.
 */
export const commitSubject = (message) => {
  const first = (message ?? "").split("\n")[0].trim();
  return first || null;
};

/** Whether a row can show anything in its commit columns. */
export const hasLastCommit = (entry) => Boolean(entry?.lastCommit?.sha);

/**
 * Where a row's commit column links to.
 *
 * Null when there is no commit to link to, so the caller renders text rather
 * than a link that goes nowhere.
 */
export const lastCommitPath = (owner, name, entry) =>
  hasLastCommit(entry) ? `/${owner}/${name}/commit/${entry.lastCommit.sha}` : null;

/**
 * The columns a listing can fill.
 *
 * If no entry resolved a commit — an unreachable history, or a walk that ran
 * past its limit — the two commit columns carry nothing for any row, and the
 * table drops them rather than ruling off two empty columns.
 */
export const showsCommitColumns = (entries) => entries.some(hasLastCommit);
