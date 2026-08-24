import { describe, expect, it } from "vitest";

import {
  commitSubject,
  hasLastCommit,
  lastCommitPath,
  showsCommitColumns,
  sortEntries,
} from "./treeEntries";

const file = (name, lastCommit) => ({ name, path: name, type: "file", mode: "100644", lastCommit });
const dir = (name, lastCommit) => ({ name, path: name, type: "dir", mode: "040000", lastCommit });
const commit = (overrides = {}) => ({
  sha: "52c260d1f4a8b3c2e5d6f70819a2b3c4d5e6f708",
  shortSha: "52c260d",
  message: "Commit number 60\n",
  authorName: "forge-demo",
  timestamp: "2026-08-01T10:00:00Z",
  ...overrides,
});

describe("directory listings", () => {
  describe("ordering", () => {
    it("puts directories before files", () => {
      const sorted = sortEntries([file("a.txt"), dir("src"), file("b.txt"), dir("docs")]);
      expect(sorted.map((e) => e.type)).toEqual(["dir", "dir", "file", "file"]);
    });

    it("keeps the server's order within each group", () => {
      const sorted = sortEntries([dir("src"), dir("docs"), file("b.txt"), file("a.txt")]);
      expect(sorted.map((e) => e.name)).toEqual(["src", "docs", "b.txt", "a.txt"]);
    });

    it("does not mutate the caller's array", () => {
      const input = [file("a.txt"), dir("src")];
      sortEntries(input);
      expect(input.map((e) => e.name)).toEqual(["a.txt", "src"]);
    });
  });

  describe("commit subject", () => {
    it("strips the trailing newline the API sends", () => {
      expect(commitSubject("Commit number 60\n")).toBe("Commit number 60");
    });

    it("takes only the first line of a multi-line message", () => {
      expect(commitSubject("Add the parser\n\nWith a longer body below.\n")).toBe("Add the parser");
    });

    it("treats an absent or blank message as nothing to show", () => {
      expect(commitSubject("")).toBeNull();
      expect(commitSubject("   \n  ")).toBeNull();
      expect(commitSubject(null)).toBeNull();
      expect(commitSubject(undefined)).toBeNull();
    });
  });

  describe("entries without a commit", () => {
    it("recognises an entry that resolved one", () => {
      expect(hasLastCommit(file("a.txt", commit()))).toBe(true);
    });

    it("treats a missing lastCommit as absent rather than failing", () => {
      expect(hasLastCommit(file("a.txt"))).toBe(false);
      expect(hasLastCommit(file("a.txt", {}))).toBe(false);
      expect(hasLastCommit(undefined)).toBe(false);
    });

    it("offers no commit link when there is no commit", () => {
      expect(lastCommitPath("o", "r", file("a.txt"))).toBeNull();
    });

    it("links to the commit by full sha, not the abbreviation", () => {
      expect(lastCommitPath("forge-demo", "long-history", file("a.txt", commit()))).toBe(
        "/forge-demo/long-history/commit/52c260d1f4a8b3c2e5d6f70819a2b3c4d5e6f708",
      );
    });
  });

  describe("whether the commit columns are shown at all", () => {
    it("shows them when any entry resolved a commit", () => {
      expect(showsCommitColumns([file("a.txt"), file("b.txt", commit())])).toBe(true);
    });

    it("drops them when none did", () => {
      expect(showsCommitColumns([file("a.txt"), dir("src")])).toBe(false);
    });

    it("drops them for an empty listing", () => {
      expect(showsCommitColumns([])).toBe(false);
    });
  });
});
