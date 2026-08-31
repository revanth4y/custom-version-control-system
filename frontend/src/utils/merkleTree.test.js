import { describe, expect, it } from "vitest";

import {
  abbreviate,
  ancestry,
  basename,
  describeEntry,
  entryAt,
  kindOf,
  merkleUrl,
  normalise,
  parentPath,
} from "./merkleTree";

const dir = (name, path, id) => ({ name, path, type: "dir", mode: "40000", id });
const file = (name, path, id, mode = "100644") => ({ name, path, type: "file", mode, id });

describe("normalise", () => {
  it("leaves an ordinary path alone", () => {
    expect(normalise("src/object/Tree.java")).toBe("src/object/Tree.java");
  });

  it("strips surrounding slashes and whitespace", () => {
    expect(normalise("/src/")).toBe("src");
    expect(normalise("  src/object  ")).toBe("src/object");
    expect(normalise("///src///")).toBe("src");
  });

  it("treats anything that is not a string as the root", () => {
    // The path arrives from a query parameter, which is null when absent.
    expect(normalise(null)).toBe("");
    expect(normalise(undefined)).toBe("");
    expect(normalise("")).toBe("");
  });
});

describe("basename and parentPath", () => {
  it("splits a nested path into its last segment and its container", () => {
    expect(basename("src/object/Tree.java")).toBe("Tree.java");
    expect(parentPath("src/object/Tree.java")).toBe("src/object");
  });

  it("treats a top-level entry as living at the root", () => {
    expect(basename("README.md")).toBe("README.md");
    expect(parentPath("README.md")).toBe("");
  });

  it("is empty at the root itself", () => {
    expect(basename("")).toBe("");
    expect(parentPath("")).toBe("");
  });
});

describe("ancestry", () => {
  it("carries the path that reaches each level", () => {
    expect(ancestry("src/object/Tree.java")).toEqual([
      { name: "src", path: "src" },
      { name: "object", path: "src/object" },
      { name: "Tree.java", path: "src/object/Tree.java" },
    ]);
  });

  it("is empty at the root, which the page shows as the commit's tree", () => {
    expect(ancestry("")).toEqual([]);
    expect(ancestry(null)).toEqual([]);
  });

  it("ignores surrounding slashes", () => {
    expect(ancestry("/src/")).toEqual([{ name: "src", path: "src" }]);
  });
});

describe("merkleUrl", () => {
  it("carries the path as a query parameter", () => {
    expect(merkleUrl("octocat", "demo", "main", "src/object")).toBe(
      "/octocat/demo/merkle/main?path=src%2Fobject",
    );
  });

  it("omits the parameter at the root", () => {
    expect(merkleUrl("octocat", "demo", "main", "")).toBe("/octocat/demo/merkle/main");
    expect(merkleUrl("octocat", "demo", "main", null)).toBe("/octocat/demo/merkle/main");
  });

  it("keeps a slashed ref and a slashed path unambiguous", () => {
    /* Both sides contain slashes; only encoding the ref as one segment and the
       path as a parameter says which is which. */
    expect(merkleUrl("octocat", "demo", "feature/login", "src/a.txt")).toBe(
      "/octocat/demo/merkle/feature%2Flogin?path=src%2Fa.txt",
    );
  });
});

describe("kindOf", () => {
  it("names a directory a tree, which is what it is in the store", () => {
    expect(kindOf(dir("src", "src", "aaa"))).toBe("tree");
  });

  it("distinguishes an executable, because the mode is part of what is hashed", () => {
    expect(kindOf(file("run.sh", "run.sh", "bbb", "100755"))).toBe("executable file");
    expect(kindOf(file("a.txt", "a.txt", "ccc", "100644"))).toBe("file");
  });

  it("is empty for nothing", () => {
    expect(kindOf(null)).toBe("");
  });
});

describe("abbreviate", () => {
  it("is the first seven characters", () => {
    expect(abbreviate("0123456789abcdef0123456789abcdef01234567")).toBe("0123456");
  });

  it("is empty for anything that is not a string", () => {
    expect(abbreviate(null)).toBe("");
    expect(abbreviate(undefined)).toBe("");
  });
});

describe("entryAt", () => {
  const entries = [
    dir("src", "src", "a".repeat(40)),
    file("README.md", "README.md", "b".repeat(40)),
  ];

  it("finds a directory's own id from its parent's listing", () => {
    /* The point of this lookup: a tree does not report its own hash, but the
       parent records it — which is the Merkle relationship being shown. */
    expect(entryAt(entries, "src").id).toBe("a".repeat(40));
  });

  it("finds a file", () => {
    expect(entryAt(entries, "README.md").id).toBe("b".repeat(40));
  });

  it("ignores surrounding slashes on either side", () => {
    expect(entryAt(entries, "/src/")).not.toBeNull();
  });

  it("is null for a path the listing does not contain", () => {
    expect(entryAt(entries, "nope")).toBeNull();
  });

  it("survives an absent listing", () => {
    expect(entryAt(null, "src")).toBeNull();
    expect(entryAt([], "src")).toBeNull();
  });
});

describe("describeEntry", () => {
  it("says what a directory is and the id its parent recorded", () => {
    const text = describeEntry(dir("src", "src", "abc1234def"));

    expect(text).toBe("Open tree src, object abc1234");
  });

  it("says a file is inspected rather than opened", () => {
    expect(describeEntry(file("a.txt", "a.txt", "def5678abc"))).toBe(
      "Inspect file a.txt, object def5678",
    );
  });

  it("distinguishes an executable in words", () => {
    expect(describeEntry(file("run.sh", "run.sh", "aaa1111bbb", "100755"))).toContain(
      "executable file",
    );
  });

  it("is empty for nothing", () => {
    expect(describeEntry(null)).toBe("");
  });
});

describe("no verification is claimed", () => {
  it("exposes nothing that computes or checks a hash", async () => {
    /* The framed bytes an id is taken over are not exposed by any endpoint, so
       the browser cannot verify anything. This pins that no such function
       quietly appears here later. */
    const module = await import("./merkleTree");
    const names = Object.keys(module).join(" ").toLowerCase();

    expect(names).not.toMatch(/verify|validate|checkhash|recompute|digest|sha1|sha256/);
  });
});
