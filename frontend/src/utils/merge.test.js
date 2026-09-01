import { describe, expect, it } from "vitest";

import {
  CONFLICT_KINDS,
  Outcome,
  countByKind,
  deletedBy,
  describeKind,
  describeRange,
  isTypeMismatch,
  movedTheBranch,
  resultFrom,
  validateMerge,
} from "./merge";
import {
  addAddConflict,
  alreadyUpToDate,
  contentConflict,
  fastForwarded,
  merged,
  modeConflict,
  modifyDeleteConflict,
  typeConflict,
} from "./__fixtures__/mergeResponses";

const only = (response) => response.conflicts[0];

describe("recorded outcomes", () => {
  it("distinguishes the three non-conflicting outcomes", () => {
    expect(alreadyUpToDate.outcome).toBe(Outcome.ALREADY_UP_TO_DATE);
    expect(fastForwarded.outcome).toBe(Outcome.FAST_FORWARDED);
    expect(merged.outcome).toBe(Outcome.MERGED);
  });

  // The distinction the interface has to make: a fast-forward moved a branch
  // pointer and created nothing, a merge created a commit with two parents.
  it("only a real merge carries a merge commit", () => {
    expect(merged.mergeCommit).toEqual(expect.any(String));
    expect(merged.tree).toEqual(expect.any(String));
    expect(fastForwarded.mergeCommit).toBeNull();
    expect(alreadyUpToDate.mergeCommit).toBeNull();
  });

  it("a fast-forward still reports where the branch now points", () => {
    expect(fastForwarded.head).toEqual(expect.any(String));
    expect(fastForwarded.head).toHaveLength(40);
  });

  it("knows which outcomes moved the branch", () => {
    expect(movedTheBranch(Outcome.MERGED)).toBe(true);
    expect(movedTheBranch(Outcome.FAST_FORWARDED)).toBe(true);
    expect(movedTheBranch(Outcome.ALREADY_UP_TO_DATE)).toBe(false);
    expect(movedTheBranch(Outcome.CONFLICTED)).toBe(false);
  });

  it("a conflict reports no commit, tree or head at all", () => {
    for (const response of [contentConflict, addAddConflict, modifyDeleteConflict, modeConflict, typeConflict]) {
      expect(response.outcome).toBe(Outcome.CONFLICTED);
      expect(response.head).toBeNull();
      expect(response.mergeCommit).toBeNull();
      expect(response.tree).toBeNull();
      expect(response.conflicts.length).toBeGreaterThan(0);
    }
  });
});

describe("conflict kinds", () => {
  it("covers every kind the engine can produce", () => {
    const produced = [contentConflict, addAddConflict, modifyDeleteConflict, modeConflict, typeConflict]
      .map((response) => only(response).kind)
      .sort();
    expect(produced).toEqual(["ADD_ADD", "CONTENT", "MODE", "MODIFY_DELETE", "TYPE"]);
    // Every kind the engine emits has a description; none is left to render raw.
    for (const kind of produced) expect(CONFLICT_KINDS[kind]).toBeDefined();
  });

  it("shows an unrecognised kind as itself rather than guessing", () => {
    expect(describeKind("SOMETHING_NEW")).toEqual({ label: "SOMETHING_NEW", summary: "" });
    expect(describeKind(undefined).label).toBe("Unknown");
  });

  /** The three sides are what identify the versions; which are absent is the data. */
  it("reads the recorded sides of each kind", () => {
    const content = only(contentConflict);
    expect(content.base).not.toBeNull();
    expect(content.ours.id).not.toBe(content.theirs.id);

    const addAdd = only(addAddConflict);
    expect(addAdd.base).toBeNull();
    expect(addAdd.ours.id).not.toBe(addAdd.theirs.id);

    // Same bytes on both sides; only the mode differs. That is the whole
    // definition of a mode conflict, and it is visible in the ids.
    const mode = only(modeConflict);
    expect(mode.ours.id).toBe(mode.theirs.id);
    expect(mode.ours.mode).not.toBe(mode.theirs.mode);

    const type = only(typeConflict);
    expect(type.ours.directory).toBe(false);
    expect(type.theirs.directory).toBe(true);
    expect(type.theirs.mode).toBe("40000");
  });
});

describe("deletedBy", () => {
  it("reads the direction of the recorded modify/delete", () => {
    const conflict = only(modifyDeleteConflict);
    expect(conflict.ours).not.toBeNull();
    expect(conflict.theirs).toBeNull();
    expect(deletedBy(conflict)).toBe("theirs");
  });

  it("reads the other direction too", () => {
    expect(deletedBy({ kind: "MODIFY_DELETE", ours: null, theirs: { id: "x" } })).toBe("ours");
  });

  it("is null for any other kind", () => {
    expect(deletedBy(only(contentConflict))).toBeNull();
    expect(deletedBy(null)).toBeNull();
  });
});

describe("isTypeMismatch", () => {
  it("is true only when one side is a directory and the other is not", () => {
    expect(isTypeMismatch(only(typeConflict))).toBe(true);
    expect(isTypeMismatch(only(contentConflict))).toBe(false);
    // A missing side is a delete, not a type mismatch.
    expect(isTypeMismatch(only(modifyDeleteConflict))).toBe(false);
  });
});

describe("countByKind", () => {
  it("counts and orders by the declared kinds", () => {
    const conflicts = [
      { kind: "TYPE" }, { kind: "CONTENT" }, { kind: "CONTENT" }, { kind: "MODE" },
    ];
    expect(countByKind(conflicts)).toEqual([
      { kind: "CONTENT", count: 2 },
      { kind: "MODE", count: 1 },
      { kind: "TYPE", count: 1 },
    ]);
  });

  it("puts unrecognised kinds last rather than dropping them", () => {
    expect(countByKind([{ kind: "WEIRD" }, { kind: "CONTENT" }])).toEqual([
      { kind: "CONTENT", count: 1 },
      { kind: "WEIRD", count: 1 },
    ]);
  });

  it("handles an empty list", () => {
    expect(countByKind([])).toEqual([]);
    expect(countByKind(undefined)).toEqual([]);
  });
});

describe("validateMerge", () => {
  const branches = [{ name: "main" }, { name: "feature" }];

  it("accepts two different existing branches", () => {
    expect(validateMerge({ target: "main", source: "feature", branches })).toBeNull();
  });

  // Sending this would be a write nobody meant to make; the engine would answer
  // "already up to date", which is true and useless.
  it("refuses merging a branch into itself", () => {
    expect(validateMerge({ target: "main", source: "main", branches })).toMatch(/into itself/i);
  });

  it("requires both sides", () => {
    expect(validateMerge({ target: "", source: "feature", branches })).toMatch(/choose/i);
    expect(validateMerge({ target: "main", source: null, branches })).toMatch(/choose/i);
  });

  it("refuses a branch that does not exist", () => {
    expect(validateMerge({ target: "main", source: "ghost", branches })).toMatch(/no branch named ghost/i);
    expect(validateMerge({ target: "ghost", source: "main", branches })).toMatch(/no branch named ghost/i);
  });

  // An empty list means the branches have not loaded, not that none exist.
  it("does not reject on an unloaded branch list", () => {
    expect(validateMerge({ target: "main", source: "feature", branches: [] })).toBeNull();
    expect(validateMerge({ target: "main", source: "feature" })).toBeNull();
  });
});

describe("resultFrom", () => {
  it("recovers the conflict body from a 409", () => {
    const error = { response: { status: 409, data: contentConflict } };
    expect(resultFrom(error)).toBe(contentConflict);
  });

  it("ignores errors that are genuinely errors", () => {
    expect(resultFrom({ response: { status: 404, data: { message: "no such branch" } } })).toBeNull();
    expect(resultFrom({ response: { status: 409, data: { code: "CONFLICT", message: "branch exists" } } })).toBeNull();
    expect(resultFrom({ message: "Network Error" })).toBeNull();
    expect(resultFrom(undefined)).toBeNull();
  });
});

describe("describeRange", () => {
  it("names a single line as one line", () => {
    expect(describeRange({ start: 7, end: 8 })).toBe("line 7");
  });

  // Ranges arrive half-open; a reader looking at the file wants the last line
  // that is actually in the range, not the one after it.
  it("names a run inclusively", () => {
    expect(describeRange({ start: 2, end: 5 })).toBe("lines 2–4");
  });

  // A real answer, not missing data: it is how a deletion reads.
  it("says so when a side contributes nothing", () => {
    expect(describeRange({ start: 4, end: 4 })).toBe("no lines");
  });

  it("does not invent a range it was not given", () => {
    expect(describeRange(undefined)).toBe("no lines");
    expect(describeRange(null)).toBe("no lines");
  });
});
