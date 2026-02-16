import { describe, expect, it } from "vitest";

import { currentFirst, filterBranches, sortBranches, subjectOf } from "./branches";

const branch = (name, timestamp, message = "a commit") => ({
  name,
  commit: "0".repeat(40),
  head: false,
  tip: timestamp === null ? null : { timestamp, message, shortSha: "abc1234", authorName: "dev" },
});

describe("subjectOf", () => {
  it("takes the first line", () => {
    expect(subjectOf("Add the object model\n\nWith a longer body.\n")).toBe("Add the object model");
  });

  // The engine normalises every message to one trailing newline, so the common
  // case is a single line that still ends in one.
  it("drops the normalised trailing newline", () => {
    expect(subjectOf("Initial commit\n")).toBe("Initial commit");
  });

  it("handles an absent message", () => {
    expect(subjectOf("")).toBe("");
    expect(subjectOf(null)).toBe("");
    expect(subjectOf(undefined)).toBe("");
  });
});

describe("filterBranches", () => {
  const branches = [branch("main", "2026-01-01T00:00:00Z"), branch("feature/login", "2026-01-02T00:00:00Z")];

  it("matches anywhere in the name, ignoring case", () => {
    expect(filterBranches(branches, "LOG").map((b) => b.name)).toEqual(["feature/login"]);
    expect(filterBranches(branches, "feature/").map((b) => b.name)).toEqual(["feature/login"]);
  });

  it("returns everything for an empty query", () => {
    expect(filterBranches(branches, "")).toHaveLength(2);
    expect(filterBranches(branches, "   ")).toHaveLength(2);
    expect(filterBranches(branches, null)).toHaveLength(2);
  });

  it("returns nothing when nothing matches", () => {
    expect(filterBranches(branches, "zzz")).toEqual([]);
  });
});

describe("sortBranches", () => {
  const older = branch("alpha", "2026-01-01T00:00:00Z");
  const newer = branch("zulu", "2026-06-01T00:00:00Z");
  const broken = branch("orphan", null);

  it("puts the most recent tip first", () => {
    expect(sortBranches([older, newer], "activity").map((b) => b.name)).toEqual(["zulu", "alpha"]);
  });

  it("sorts by name when asked", () => {
    expect(sortBranches([newer, older], "name").map((b) => b.name)).toEqual(["alpha", "zulu"]);
  });

  // A reference whose commit cannot be read has no date. Treating that as
  // infinitely old is what keeps it out of the top slot.
  it("sorts a branch with no readable tip last by activity", () => {
    expect(sortBranches([broken, older, newer], "activity").map((b) => b.name)).toEqual([
      "zulu",
      "alpha",
      "orphan",
    ]);
  });

  it("breaks ties on name so the order is stable", () => {
    const b = branch("beta", "2026-01-01T00:00:00Z");
    const a = branch("alpha", "2026-01-01T00:00:00Z");
    expect(sortBranches([b, a], "activity").map((x) => x.name)).toEqual(["alpha", "beta"]);
    expect(sortBranches([a, b], "activity").map((x) => x.name)).toEqual(["alpha", "beta"]);
  });

  it("does not mutate the input", () => {
    const input = [newer, older];
    sortBranches(input, "name");
    expect(input.map((b) => b.name)).toEqual(["zulu", "alpha"]);
  });
});

describe("currentFirst", () => {
  const branches = [branch("alpha"), branch("main"), branch("zulu")];

  it("moves the HEAD branch to the front, preserving the rest", () => {
    expect(currentFirst(branches, "main").map((b) => b.name)).toEqual(["main", "alpha", "zulu"]);
  });

  it("leaves the order alone when HEAD is unknown or absent from the list", () => {
    expect(currentFirst(branches, null).map((b) => b.name)).toEqual(["alpha", "main", "zulu"]);
    expect(currentFirst(branches, "detached").map((b) => b.name)).toEqual(["alpha", "main", "zulu"]);
  });
});
