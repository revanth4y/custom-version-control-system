/**
 * Reading and filtering issues.
 *
 * The permission helpers here mirror Issue.isEditableBy and
 * IssueComment.isEditableBy on the server. They decide what to *show*, and
 * nothing else: every write is authorised again by the server, which is the
 * only authority. Hiding a control the server would refuse is a courtesy, not
 * a defence.
 */

export const IssueStatus = {
  OPEN: "OPEN",
  CLOSED: "CLOSED",
};

/** The filter shown above the list. "all" is a view, not a server status. */
export const StatusFilter = {
  OPEN: "open",
  CLOSED: "closed",
  ALL: "all",
};

/**
 * Turns whatever is in the URL into a filter this page understands.
 *
 * Anything unrecognised falls back to open rather than being sent onward: the
 * server rejects an unknown status, and a stale or hand-edited link should show
 * something sensible instead of an error.
 */
export function normaliseStatusFilter(raw) {
  const value = (raw ?? "").trim().toLowerCase();
  return Object.values(StatusFilter).includes(value) ? value : StatusFilter.OPEN;
}

/**
 * Matches an issue against a search box.
 *
 * Numbers are matched as well as titles, and a leading # is optional, because
 * "#12" and "12" are both how people refer to an issue. A numeric query has to
 * match the number exactly - typing 1 should not return every issue from 1 to
 * 19 - while text is matched anywhere in the title.
 */
export function matchesQuery(issue, rawQuery) {
  const query = (rawQuery ?? "").trim().toLowerCase();
  if (!query) return true;

  const asNumber = query.startsWith("#") ? query.slice(1) : query;
  if (/^\d+$/.test(asNumber)) {
    return String(issue.number) === asNumber;
  }
  return (issue.title ?? "").toLowerCase().includes(query);
}

export function filterIssues(issues, { status, query } = {}) {
  const filter = normaliseStatusFilter(status);
  return (issues ?? []).filter((issue) => {
    if (filter === StatusFilter.OPEN && issue.status !== IssueStatus.OPEN) return false;
    if (filter === StatusFilter.CLOSED && issue.status !== IssueStatus.CLOSED) return false;
    return matchesQuery(issue, query);
  });
}

/**
 * Open and closed tallies.
 *
 * Counted here because no endpoint reports them, and the whole list is fetched
 * anyway - there is no pagination, so a second request would buy nothing.
 */
export function countByStatus(issues) {
  const list = issues ?? [];
  const open = list.filter((issue) => issue.status === IssueStatus.OPEN).length;
  return { open, closed: list.length - open, total: list.length };
}

const sameUser = (username, viewer) =>
  Boolean(username) && Boolean(viewer) && username === viewer.username;

const ownsRepository = (viewer, repository) =>
  Boolean(viewer) && Boolean(repository) && repository.ownerUsername === viewer.username;

/** Mirrors Issue.isEditableBy: the author, or the repository owner. */
export function canEditIssue(issue, viewer, repository) {
  if (!issue || !viewer) return false;
  return sameUser(issue.authorUsername, viewer) || ownsRepository(viewer, repository);
}

/** Mirrors IssueComment.isEditableBy: the author, or the repository owner. */
export function canEditComment(comment, viewer, repository) {
  if (!comment || !viewer) return false;
  return sameUser(comment.authorUsername, viewer) || ownsRepository(viewer, repository);
}

/**
 * Whether anyone signed in may open an issue or comment here.
 *
 * Deliberately not the same rule as branches or merges: those are the owner's
 * alone, while any authenticated reader may join a discussion. Reusing the
 * owner check here would silently hide the feature from everyone else.
 */
export function canParticipate(viewer) {
  return Boolean(viewer);
}

/**
 * Whether a body was changed after it was written.
 *
 * The server sets both timestamps together on creation, so any later difference
 * is an edit. Note that a PATCH response is serialised before the transaction
 * flushes and comes back with the old timestamp - so this is only meaningful on
 * freshly fetched data, which is why edits refetch rather than trust the reply.
 */
export function wasEdited(entity) {
  if (!entity?.createdAt || !entity?.updatedAt) return false;
  return Date.parse(entity.updatedAt) > Date.parse(entity.createdAt);
}

/** The name to show for a record whose author account may be gone. */
export function authorLabel(authorUsername) {
  return authorUsername ?? "a deleted account";
}
