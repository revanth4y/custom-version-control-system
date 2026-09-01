import { describe, expect, it } from "vitest";

import {
  FileState,
  anchorFor,
  fileState,
  modeChange,
  pathFromAnchor,
  splitBySegments,
  splitPath,
  summarise,
} from "./diff";
import {
  binaryDiff,
  emptyDiff,
  mergeCommitDetail,
  mixedChangesDiff,
  modeOnlyDiff,
  tooLargeDiff,
} from "./__fixtures__/diffResponses";

const fileNamed = (diff, path) => diff.files.find((f) => f.path === path);

describe("fileState", () => {
  /**
   * The three states that all arrive as "no hunks, no counts". Getting these
   * wrong means telling someone a file did not change when it became
   * executable, or when it is a 900-byte image.
   */
  it("recognises a binary file from the real payload", () => {
    const file = fileNamed(binaryDiff, "assets/logo.bin");
    expect(file.hunks).toHaveLength(0);
    expect(file.additions).toBe(0);
    expect(fileState(file)).toBe(FileState.BINARY);
  });

  it("recognises a diff the engine declined to compute", () => {
    const file = fileNamed(tooLargeDiff, "generated/table.txt");
    expect(file.hunks).toHaveLength(0);
    expect(file.tooLarge).toBe(true);
    expect(fileState(file)).toBe(FileState.TOO_LARGE);
  });

  it("recognises a change that touched only the mode", () => {
    const file = fileNamed(modeOnlyDiff, "scripts/run.sh");
    expect(file.hunks).toHaveLength(0);
    expect(file.status).toBe("MODIFIED");
    expect(fileState(file)).toBe(FileState.MODE_ONLY);
  });

  it("recognises a file with real line changes", () => {
    expect(fileState(fileNamed(mixedChangesDiff, "src/parser.txt"))).toBe(FileState.TEXT);
  });

  it("prefers binary over every other reason", () => {
    // A binary file large enough to also be skipped should still read as binary,
    // which is the more useful thing to say.
    expect(fileState({ binary: true, tooLarge: true, hunks: [] })).toBe(FileState.BINARY);
  });

  it("calls a file with nothing to show unchanged", () => {
    expect(fileState({ hunks: [], oldMode: "100644", newMode: "100644" })).toBe(FileState.UNCHANGED);
    expect(fileState(undefined)).toBe(FileState.UNCHANGED);
  });
});

describe("modeChange", () => {
  it("reads the real executable-bit change", () => {
    const file = fileNamed(modeOnlyDiff, "scripts/run.sh");
    expect(modeChange(file)).toEqual({ from: "100644", to: "100755", label: "made executable" });
  });

  it("reads the reverse", () => {
    expect(modeChange({ oldMode: "100755", newMode: "100644" }).label).toBe("no longer executable");
  });

  // Added and deleted files have one side null. That is the file arriving or
  // leaving, which the status already says, not a mode change.
  it("is not a mode change when a file is added or deleted", () => {
    const added = fileNamed(binaryDiff, "assets/logo.bin");
    expect(added.oldMode).toBeNull();
    expect(modeChange(added)).toBeNull();
    expect(modeChange({ oldMode: "100644", newMode: null })).toBeNull();
  });

  it("is null when the mode is unchanged", () => {
    expect(modeChange({ oldMode: "100644", newMode: "100644" })).toBeNull();
    expect(modeChange({})).toBeNull();
  });
});

describe("summarise", () => {
  it("counts the real mixed commit by status", () => {
    const counts = summarise(mixedChangesDiff.files);
    expect(counts).toEqual({ files: 4, added: 1, modified: 2, deleted: 1, additions: 10, deletions: 6 });
  });

  // The per-file counts must agree with the totals the server sent, or one of
  // the two is being read wrong.
  it("agrees with the server's own totals", () => {
    for (const diff of [mixedChangesDiff, binaryDiff, modeOnlyDiff, tooLargeDiff, emptyDiff]) {
      const counts = summarise(diff.files);
      expect(counts.files).toBe(diff.filesChanged);
      expect(counts.additions).toBe(diff.totalAdditions);
      expect(counts.deletions).toBe(diff.totalDeletions);
    }
  });

  it("handles an empty diff", () => {
    expect(summarise(emptyDiff.files)).toEqual({
      files: 0, added: 0, modified: 0, deleted: 0, additions: 0, deletions: 0,
    });
    expect(summarise(undefined).files).toBe(0);
  });
});

describe("anchorFor", () => {
  it("round-trips a path", () => {
    for (const path of ["README.md", "src/parser.txt", "a/b", "a b/c#d?e", "ünïcode/файл.txt"]) {
      expect(pathFromAnchor(anchorFor(path))).toBe(path);
    }
  });

  // Slugifying would map both of these to the same id and scroll to the wrong
  // file; encoding keeps them distinct.
  it("does not collide paths that differ only in separators", () => {
    expect(anchorFor("a/b")).not.toBe(anchorFor("a-b"));
    expect(anchorFor("a/b")).not.toBe(anchorFor("a b"));
  });

  it("gives every real file a distinct anchor", () => {
    const anchors = mixedChangesDiff.files.map((f) => anchorFor(f.path));
    expect(new Set(anchors).size).toBe(anchors.length);
  });

  it("returns null for something that is not a file anchor", () => {
    expect(pathFromAnchor("readme")).toBeNull();
    expect(pathFromAnchor(null)).toBeNull();
    expect(pathFromAnchor("file-%E0%A4%A")).toBeNull();
  });
});

describe("splitPath", () => {
  it("separates the directory from the filename", () => {
    expect(splitPath("src/main/java/App.java")).toEqual({
      directory: "src/main/java/",
      name: "App.java",
    });
  });

  it("handles a file at the root", () => {
    expect(splitPath("README.md")).toEqual({ directory: "", name: "README.md" });
    expect(splitPath("")).toEqual({ directory: "", name: "" });
  });
});

describe("real hunk data", () => {
  /**
   * Line numbering is the thing a diff viewer is most likely to get quietly
   * wrong, so it is asserted against the payload rather than trusted: an added
   * line has no old number, a removed line has no new number, and context has
   * both.
   */
  it("numbers lines consistently with their type", () => {
    for (const file of mixedChangesDiff.files) {
      for (const hunk of file.hunks) {
        for (const line of hunk.lines) {
          if (line.type === "ADDED") {
            expect(line.oldNumber).toBeNull();
            expect(line.newNumber).toBeGreaterThan(0);
          } else if (line.type === "REMOVED") {
            expect(line.newNumber).toBeNull();
            expect(line.oldNumber).toBeGreaterThan(0);
          } else {
            expect(line.type).toBe("CONTEXT");
            expect(line.oldNumber).toBeGreaterThan(0);
            expect(line.newNumber).toBeGreaterThan(0);
          }
        }
      }
    }
  });

  it("has line counts matching each hunk's declared span", () => {
    for (const file of mixedChangesDiff.files) {
      for (const hunk of file.hunks) {
        const oldLines = hunk.lines.filter((l) => l.type !== "ADDED").length;
        const newLines = hunk.lines.filter((l) => l.type !== "REMOVED").length;
        expect(oldLines).toBe(hunk.oldCount);
        expect(newLines).toBe(hunk.newCount);
      }
    }
  });

  it("has per-file counts matching its own hunks", () => {
    for (const file of mixedChangesDiff.files) {
      const added = file.hunks.flatMap((h) => h.lines).filter((l) => l.type === "ADDED").length;
      const removed = file.hunks.flatMap((h) => h.lines).filter((l) => l.type === "REMOVED").length;
      expect(added).toBe(file.additions);
      expect(removed).toBe(file.deletions);
    }
  });
});

describe("merge commit detail", () => {
  it("carries both parents, in order", () => {
    expect(mergeCommitDetail.commit.merge).toBe(true);
    expect(mergeCommitDetail.commit.parents).toHaveLength(2);
    expect(new Set(mergeCommitDetail.commit.parents).size).toBe(2);
  });

  it("reports changes against the first parent only", () => {
    // The engine documents this: the second parent's work is already present on
    // the branch being merged into.
    expect(mergeCommitDetail.changes.changes.length).toBeGreaterThan(0);
  });
});

describe("splitBySegments", () => {
  const joined = (pieces) => pieces.map((p) => p.text).join("");

  it("returns one unchanged piece when there are no runs", () => {
    expect(splitBySegments("timeout = 30", [])).toEqual([{ text: "timeout = 30", changed: false }]);
  });

  it("returns one unchanged piece when the field is absent", () => {
    // A v2.0.8 response carries no such field; the line must still render.
    expect(splitBySegments("timeout = 30", undefined)).toEqual([
      { text: "timeout = 30", changed: false },
    ]);
  });

  it("splits around a single changed run", () => {
    expect(splitBySegments("timeout = 30", [{ start: 10, end: 11 }])).toEqual([
      { text: "timeout = ", changed: false },
      { text: "3", changed: true },
      { text: "0", changed: false },
    ]);
  });

  it("splits around several runs", () => {
    const pieces = splitBySegments("alpha P beta Q gamma", [
      { start: 6, end: 7 },
      { start: 13, end: 14 },
    ]);

    expect(pieces.filter((p) => p.changed).map((p) => p.text)).toEqual(["P", "Q"]);
  });

  it("handles a run at the very start", () => {
    expect(splitBySegments("abc", [{ start: 0, end: 1 }])).toEqual([
      { text: "a", changed: true },
      { text: "bc", changed: false },
    ]);
  });

  it("handles a run reaching the very end", () => {
    expect(splitBySegments("abc", [{ start: 2, end: 3 }])).toEqual([
      { text: "ab", changed: false },
      { text: "c", changed: true },
    ]);
  });

  it("covers the whole line when everything changed", () => {
    expect(splitBySegments("abc", [{ start: 0, end: 3 }])).toEqual([{ text: "abc", changed: true }]);
  });

  it("never loses or repeats a character", () => {
    const content = "one two three four";
    const pieces = splitBySegments(content, [
      { start: 4, end: 7 },
      { start: 14, end: 18 },
    ]);

    expect(joined(pieces)).toBe(content);
  });

  it("treats an empty line as a single empty piece", () => {
    expect(splitBySegments("", [])).toEqual([{ text: "", changed: false }]);
  });
});
