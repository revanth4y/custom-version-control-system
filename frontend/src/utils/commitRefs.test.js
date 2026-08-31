import { describe, expect, it } from "vitest";

import { abbreviate, describeNode, indexRefs, refsFor } from "./commitRefs";

const branch = (name, commit) => ({ name, commit, head: false, tip: null });

/** A row shaped like buildCommitGraph's output. */
const node = (overrides = {}) => ({
  sha: "a".repeat(40),
  shortSha: "aaaaaaa",
  commit: { message: "Add the thing\n" },
  row: 0,
  lane: 0,
  parents: [],
  isMerge: false,
  isRoot: true,
  boundaryParents: [],
  ...overrides,
});

describe("indexRefs", () => {
  it("associates a branch with the commit it points at", () => {
    const index = indexRefs([branch("main", "abc")], { branch: "main" });

    expect(refsFor(index, "abc")).toEqual([{ name: "main", isHead: true }]);
  });

  it("marks only the branch HEAD is on", () => {
    const index = indexRefs([branch("main", "abc"), branch("side", "def")], { branch: "main" });

    expect(refsFor(index, "abc")[0].isHead).toBe(true);
    expect(refsFor(index, "def")[0].isHead).toBe(false);
  });

  it("carries several branches on one commit", () => {
    // Ordinary after branching without committing, not a conflict.
    const index = indexRefs([branch("main", "abc"), branch("side", "abc")], { branch: "main" });

    expect(refsFor(index, "abc").map((r) => r.name)).toEqual(["main", "side"]);
  });

  it("sorts names so the same repository labels a node the same way twice", () => {
    const once = indexRefs([branch("zeta", "abc"), branch("alpha", "abc")], null);
    const twice = indexRefs([branch("alpha", "abc"), branch("zeta", "abc")], null);

    expect(once.get("abc")).toEqual(twice.get("abc"));
    expect(once.get("abc").map((r) => r.name)).toEqual(["alpha", "zeta"]);
  });

  it("has nothing for a commit no branch points at", () => {
    const index = indexRefs([branch("main", "abc")], { branch: "main" });

    expect(refsFor(index, "unreferenced")).toEqual([]);
  });

  it("skips a branch with no tip rather than inventing one", () => {
    // An unborn branch names no commit, so there is no node to attach it to.
    const index = indexRefs([{ name: "empty", commit: null }, branch("main", "abc")], null);

    expect(index.size).toBe(1);
    expect(refsFor(index, "abc").map((r) => r.name)).toEqual(["main"]);
  });

  it("survives absent input", () => {
    expect(indexRefs(null, null).size).toBe(0);
    expect(indexRefs([], null).size).toBe(0);
    expect(refsFor(null, "abc")).toEqual([]);
  });

  it("marks no branch as HEAD when HEAD names none", () => {
    const index = indexRefs([branch("main", "abc")], null);

    expect(refsFor(index, "abc")[0].isHead).toBe(false);
  });
});

describe("describeNode", () => {
  it("names the commit and its message", () => {
    const text = describeNode(node());

    expect(text).toContain("Commit aaaaaaa");
    expect(text).toContain("Add the thing");
  });

  it("says a root commit has no parents", () => {
    expect(describeNode(node({ isRoot: true, parents: [] }))).toContain("root commit, no parents");
  });

  it("says how many parents a merge has, and names them", () => {
    /* The visual difference is a hollow ring, which describes as nothing at
       all. The count and the ids are what actually carry the meaning. */
    const text = describeNode(
      node({
        isMerge: true,
        isRoot: false,
        parents: ["b".repeat(40), "c".repeat(40)],
      }),
    );

    expect(text).toContain("merge of 2 parents");
    expect(text).toContain("parents bbbbbbb, ccccccc");
  });

  it("names the single parent of an ordinary commit", () => {
    const text = describeNode(node({ isRoot: false, parents: ["d".repeat(40)] }));

    expect(text).toContain("parent ddddddd");
  });

  it("states that a parent has not been loaded", () => {
    // The fading stub says this visually and conveys nothing otherwise.
    const text = describeNode(
      node({ isRoot: false, parents: ["e".repeat(40)], boundaryParents: ["e".repeat(40)] }),
    );

    expect(text).toContain("1 parent not loaded yet");
  });

  it("pluralises unloaded parents", () => {
    const text = describeNode(
      node({
        isMerge: true,
        isRoot: false,
        parents: ["e".repeat(40), "f".repeat(40)],
        boundaryParents: ["e".repeat(40), "f".repeat(40)],
      }),
    );

    expect(text).toContain("2 parents not loaded yet");
  });

  it("includes the refs pointing at it, marking HEAD", () => {
    const text = describeNode(node(), [
      { name: "main", isHead: true },
      { name: "side", isHead: false },
    ]);

    expect(text).toContain("main (HEAD)");
    expect(text).toContain("side");
  });

  it("uses only the first line of a multi-line message", () => {
    const text = describeNode(node({ commit: { message: "Subject\n\nBody paragraph.\n" } }));

    expect(text).toContain("Subject");
    expect(text).not.toContain("Body paragraph");
  });

  it("copes with a commit that has no message", () => {
    expect(describeNode(node({ commit: { message: "" } }))).toContain("Commit aaaaaaa");
  });

  it("is empty for no node at all", () => {
    expect(describeNode(null)).toBe("");
  });
});

describe("abbreviate", () => {
  it("is the first seven characters", () => {
    expect(abbreviate("0123456789abcdef")).toBe("0123456");
  });

  it("is empty for anything that is not a string", () => {
    expect(abbreviate(null)).toBe("");
    expect(abbreviate(undefined)).toBe("");
  });
});
