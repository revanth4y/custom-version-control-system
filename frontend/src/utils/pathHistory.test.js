import { describe, expect, it } from "vitest";

import { basename, blobUrlAt, isFiltered, normalisePath, pathHistoryUrl } from "./pathHistory";

describe("normalisePath", () => {
  it("leaves an ordinary path alone", () => {
    expect(normalisePath("src/App.java")).toBe("src/App.java");
  });

  it("strips surrounding slashes and whitespace", () => {
    expect(normalisePath("/src/")).toBe("src");
    expect(normalisePath("  src/App.java  ")).toBe("src/App.java");
    expect(normalisePath("///src///")).toBe("src");
  });

  it("treats anything that is not a string as no path", () => {
    // The path arrives from a URL parameter, which is null when absent.
    expect(normalisePath(null)).toBe("");
    expect(normalisePath(undefined)).toBe("");
    expect(normalisePath("")).toBe("");
    expect(normalisePath("   ")).toBe("");
  });

  it("keeps interior slashes, which are real separators", () => {
    expect(normalisePath("a/b/c.txt")).toBe("a/b/c.txt");
  });
});

describe("isFiltered", () => {
  it("is false for the root, which every commit touches", () => {
    expect(isFiltered("")).toBe(false);
    expect(isFiltered("/")).toBe(false);
    expect(isFiltered(null)).toBe(false);
  });

  it("is true for an actual path", () => {
    expect(isFiltered("README.md")).toBe(true);
    expect(isFiltered("/src/")).toBe(true);
  });
});

describe("pathHistoryUrl", () => {
  it("carries the path as a query parameter", () => {
    expect(pathHistoryUrl("octocat", "demo", "main", "src/App.java")).toBe(
      "/octocat/demo/commits/main?path=src%2FApp.java",
    );
  });

  it("omits the parameter entirely for the root", () => {
    // Sending path= would mean the same thing, and read as a filter that found
    // nothing rather than as no filter at all.
    expect(pathHistoryUrl("octocat", "demo", "main", "")).toBe("/octocat/demo/commits/main");
    expect(pathHistoryUrl("octocat", "demo", "main", null)).toBe("/octocat/demo/commits/main");
  });

  it("encodes a ref containing slashes", () => {
    expect(pathHistoryUrl("octocat", "demo", "feature/login", "a.txt")).toBe(
      "/octocat/demo/commits/feature%2Flogin?path=a.txt",
    );
  });

  it("keeps a slashed ref and a slashed path unambiguous", () => {
    /* The reason the path is not a route segment. Both sides contain slashes,
       and only a query parameter says which is which. */
    const url = pathHistoryUrl("octocat", "demo", "feature/login", "src/main/App.java");
    expect(url).toBe("/octocat/demo/commits/feature%2Flogin?path=src%2Fmain%2FApp.java");
  });

  it("normalises before building", () => {
    expect(pathHistoryUrl("octocat", "demo", "main", "/src/")).toBe(
      "/octocat/demo/commits/main?path=src",
    );
  });
});

describe("blobUrlAt", () => {
  it("points at the file as of one commit", () => {
    expect(blobUrlAt("octocat", "demo", "abc123", "src/App.java")).toBe(
      "/octocat/demo/blob/abc123/src/App.java",
    );
  });

  it("encodes the ref but not the path", () => {
    // The path's slashes are separators the splat route absorbs; the ref is a
    // single segment and a slash in it would otherwise invent one.
    expect(blobUrlAt("octocat", "demo", "feature/login", "a/b.txt")).toBe(
      "/octocat/demo/blob/feature%2Flogin/a/b.txt",
    );
  });
});

describe("basename", () => {
  it("is the last segment", () => {
    expect(basename("src/main/App.java")).toBe("App.java");
    expect(basename("README.md")).toBe("README.md");
  });

  it("is empty for no path", () => {
    expect(basename("")).toBe("");
    expect(basename(null)).toBe("");
  });

  it("ignores a trailing slash on a directory", () => {
    expect(basename("src/main/")).toBe("main");
  });
});
