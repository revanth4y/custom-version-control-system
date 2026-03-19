import { describe, expect, it } from "vitest";

import {
  IssueStatus,
  StatusFilter,
  authorLabel,
  canEditComment,
  canEditIssue,
  canParticipate,
  countByStatus,
  filterIssues,
  matchesQuery,
  normaliseStatusFilter,
  wasEdited,
} from "./issues";
import { commentThread, issueList } from "./__fixtures__/issueResponses";

const numbersOf = (issues) => issues.map((issue) => issue.number);
const byNumber = (n) => issueList.find((issue) => issue.number === n);

const owner = { username: "forge-dev" };
const viewer = { username: "forge-viewer" };
const stranger = { username: "someone-else" };
const repository = { ownerUsername: "forge-dev" };

describe("normaliseStatusFilter", () => {
  it("accepts the three real filters", () => {
    expect(normaliseStatusFilter("open")).toBe(StatusFilter.OPEN);
    expect(normaliseStatusFilter("closed")).toBe(StatusFilter.CLOSED);
    expect(normaliseStatusFilter("all")).toBe(StatusFilter.ALL);
  });

  it("is case and whitespace tolerant", () => {
    expect(normaliseStatusFilter("  CLOSED ")).toBe(StatusFilter.CLOSED);
  });

  // A stale or hand-edited link should show something sensible. Passing an
  // unknown value on to the server is what produced a 500 before it was fixed.
  it("falls back to open for anything unrecognised", () => {
    expect(normaliseStatusFilter("bogus")).toBe(StatusFilter.OPEN);
    expect(normaliseStatusFilter("")).toBe(StatusFilter.OPEN);
    expect(normaliseStatusFilter(null)).toBe(StatusFilter.OPEN);
    expect(normaliseStatusFilter(undefined)).toBe(StatusFilter.OPEN);
  });
});

describe("matchesQuery", () => {
  const issue = byNumber(2);

  it("matches text anywhere in the title, ignoring case", () => {
    expect(matchesQuery(issue, "lanes")).toBe(true);
    expect(matchesQuery(issue, "LANES")).toBe(true);
    expect(matchesQuery(issue, "zzzz")).toBe(false);
  });

  // Both are how people refer to an issue out loud.
  it("matches an issue number with or without the hash", () => {
    expect(matchesQuery(issue, "2")).toBe(true);
    expect(matchesQuery(issue, "#2")).toBe(true);
    expect(matchesQuery(issue, " #2 ")).toBe(true);
  });

  // Typing 1 should not return issues 1, 10 and 12 together.
  it("matches a number exactly rather than as a prefix", () => {
    expect(matchesQuery({ number: 12, title: "" }, "1")).toBe(false);
    expect(matchesQuery({ number: 12, title: "" }, "12")).toBe(true);
  });

  it("does not confuse a number query with title text", () => {
    // The title contains no digits, so a numeric query must fall to the number.
    expect(matchesQuery({ number: 5, title: "release 12 notes" }, "12")).toBe(false);
  });

  it("matches everything when the query is empty", () => {
    expect(matchesQuery(issue, "")).toBe(true);
    expect(matchesQuery(issue, "   ")).toBe(true);
    expect(matchesQuery(issue, undefined)).toBe(true);
  });

  it("tolerates an issue with no title", () => {
    expect(matchesQuery({ number: 1 }, "anything")).toBe(false);
  });
});

describe("filterIssues", () => {
  it("shows only open issues by default", () => {
    const open = filterIssues(issueList, { status: StatusFilter.OPEN });
    expect(open.every((issue) => issue.status === IssueStatus.OPEN)).toBe(true);
    expect(numbersOf(open)).toEqual([7, 6, 4, 2, 1]);
  });

  it("shows only closed issues when asked", () => {
    const closed = filterIssues(issueList, { status: StatusFilter.CLOSED });
    expect(numbersOf(closed)).toEqual([5, 3]);
  });

  it("shows everything under all", () => {
    expect(filterIssues(issueList, { status: StatusFilter.ALL })).toHaveLength(issueList.length);
  });

  it("preserves the order the server returned", () => {
    // Newest number first, which is what the endpoint promises.
    expect(numbersOf(filterIssues(issueList, { status: StatusFilter.ALL }))).toEqual([7, 6, 5, 4, 3, 2, 1]);
  });

  it("combines status and search", () => {
    const found = filterIssues(issueList, { status: StatusFilter.CLOSED, query: "binary" });
    expect(numbersOf(found)).toEqual([3]);
    // The same search under the open filter finds nothing, because #3 is closed.
    expect(filterIssues(issueList, { status: StatusFilter.OPEN, query: "binary" })).toEqual([]);
  });

  it("finds a closed issue by number only under a matching filter", () => {
    expect(numbersOf(filterIssues(issueList, { status: StatusFilter.ALL, query: "#3" }))).toEqual([3]);
    expect(filterIssues(issueList, { status: StatusFilter.OPEN, query: "#3" })).toEqual([]);
  });

  it("handles an absent list", () => {
    expect(filterIssues(undefined, {})).toEqual([]);
    expect(filterIssues(null, { query: "x" })).toEqual([]);
  });
});

describe("countByStatus", () => {
  it("counts the recorded list", () => {
    expect(countByStatus(issueList)).toEqual({ open: 5, closed: 2, total: 7 });
  });

  it("always adds up", () => {
    const counts = countByStatus(issueList);
    expect(counts.open + counts.closed).toBe(counts.total);
  });

  it("handles an empty list", () => {
    expect(countByStatus([])).toEqual({ open: 0, closed: 0, total: 0 });
    expect(countByStatus(undefined).total).toBe(0);
  });
});

describe("canEditIssue", () => {
  const mine = byNumber(3);      // authored by forge-viewer
  const theirs = byNumber(2);    // authored by forge-dev, who owns the repo
  const orphaned = byNumber(6);  // author account deleted

  it("lets the author edit their own issue", () => {
    expect(canEditIssue(mine, viewer, repository)).toBe(true);
  });

  it("lets the repository owner edit anyone's issue", () => {
    expect(canEditIssue(mine, owner, repository)).toBe(true);
  });

  it("refuses anyone else", () => {
    expect(canEditIssue(theirs, viewer, repository)).toBe(false);
    expect(canEditIssue(mine, stranger, repository)).toBe(false);
  });

  it("refuses anonymous viewers", () => {
    expect(canEditIssue(mine, null, repository)).toBe(false);
    expect(canEditIssue(mine, undefined, repository)).toBe(false);
  });

  // The author is null once the account is gone. Nobody inherits their
  // authorship, but the repository owner can still moderate.
  it("does not let a deleted author's record be claimed", () => {
    expect(orphaned.authorUsername).toBeNull();
    expect(canEditIssue(orphaned, viewer, repository)).toBe(false);
    expect(canEditIssue(orphaned, stranger, repository)).toBe(false);
    expect(canEditIssue(orphaned, owner, repository)).toBe(true);
  });
});

describe("canEditComment", () => {
  const orphan = commentThread.find((comment) => comment.authorUsername === null);
  const byViewer = commentThread.find((comment) => comment.authorUsername === "forge-viewer");

  it("lets the author edit their own comment", () => {
    expect(canEditComment(byViewer, viewer, repository)).toBe(true);
  });

  it("lets the repository owner moderate another user's comment", () => {
    expect(canEditComment(byViewer, owner, repository)).toBe(true);
  });

  it("refuses anyone else, and anonymous", () => {
    expect(canEditComment(byViewer, stranger, repository)).toBe(false);
    expect(canEditComment(byViewer, null, repository)).toBe(false);
  });

  it("leaves an orphaned comment to the repository owner alone", () => {
    expect(orphan).toBeDefined();
    expect(canEditComment(orphan, viewer, repository)).toBe(false);
    expect(canEditComment(orphan, owner, repository)).toBe(true);
  });
});

describe("canParticipate", () => {
  // Any signed-in reader may open an issue - not only the owner, which is the
  // rule for branches and merges.
  it("is true for any signed-in user", () => {
    expect(canParticipate(viewer)).toBe(true);
    expect(canParticipate(stranger)).toBe(true);
  });

  it("is false for anonymous", () => {
    expect(canParticipate(null)).toBe(false);
    expect(canParticipate(undefined)).toBe(false);
  });
});

describe("wasEdited", () => {
  it("is false for a record that was never touched", () => {
    const untouched = issueList.find((issue) => issue.createdAt === issue.updatedAt);
    expect(untouched).toBeDefined();
    expect(wasEdited(untouched)).toBe(false);
  });

  it("is true once the update timestamp has moved", () => {
    expect(wasEdited({ createdAt: "2026-01-01T00:00:00Z", updatedAt: "2026-01-01T00:00:05Z" })).toBe(true);
  });

  it("handles missing timestamps", () => {
    expect(wasEdited({})).toBe(false);
    expect(wasEdited(null)).toBe(false);
  });
});

describe("authorLabel", () => {
  it("names the author when there is one", () => {
    expect(authorLabel("forge-dev")).toBe("forge-dev");
  });

  // The schema clears the author rather than cascading, so a conversation
  // survives the account that started it.
  it("says plainly when the account is gone", () => {
    expect(authorLabel(null)).toBe("a deleted account");
    expect(authorLabel(undefined)).toBe("a deleted account");
  });
});
