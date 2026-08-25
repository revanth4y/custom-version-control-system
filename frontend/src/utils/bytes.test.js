import { describe, expect, it } from "vitest";

import { extensionOf, formatBytes, toLines } from "./bytes";

describe("formatBytes", () => {
  it("shows whole bytes without a decimal", () => {
    expect(formatBytes(0)).toBe("0 B");
    expect(formatBytes(1)).toBe("1 B");
    expect(formatBytes(1023)).toBe("1023 B");
  });

  it("steps up a unit at 1024", () => {
    expect(formatBytes(1024)).toBe("1 KB");
    expect(formatBytes(1536)).toBe("1.5 KB");
    expect(formatBytes(1024 * 1024)).toBe("1 MB");
    expect(formatBytes(1024 * 1024 * 1024)).toBe("1 GB");
  });

  // Nothing larger than GB is defined, so very large sizes must stay in GB
  // rather than running off the end of the unit list.
  it("stops at the largest known unit", () => {
    expect(formatBytes(1024 ** 4)).toBe("1024 GB");
  });

  it("returns nothing for an absent size", () => {
    expect(formatBytes(null)).toBe("");
    expect(formatBytes(undefined)).toBe("");
    expect(formatBytes(NaN)).toBe("");
  });
});

describe("toLines", () => {
  it("splits on newlines", () => {
    expect(toLines("a\nb\nc")).toEqual(["a", "b", "c"]);
  });

  // A file almost always ends with a newline; splitting naively would report an
  // extra empty line and an off-by-one line count.
  it("does not invent a line for the trailing newline", () => {
    expect(toLines("a\nb\n")).toEqual(["a", "b"]);
  });

  it("keeps genuine blank lines", () => {
    expect(toLines("a\n\nb\n")).toEqual(["a", "", "b"]);
    expect(toLines("a\n\n")).toEqual(["a", ""]);
  });

  it("treats empty content as no lines", () => {
    expect(toLines("")).toEqual([]);
    expect(toLines(null)).toEqual([]);
  });

  /* The counting has to hold at the size where it starts to matter: past five
     thousand lines the blob view drops the number gutter, and it decides that
     from this count. Being one line out here would move that boundary. */
  it("counts a file large enough to lose its line numbers", () => {
    const lines = toLines(
      Array.from({ length: 5001 }, (_, index) => `line ${index + 1}`).join("\n") + "\n",
    );

    expect(lines).toHaveLength(5001);
    expect(lines[0]).toBe("line 1");
    expect(lines.at(-1)).toBe("line 5001");
  });
});

describe("extensionOf", () => {
  it("reads the extension of a nested path", () => {
    expect(extensionOf("src/main/java/Sha1.java")).toBe("java");
    expect(extensionOf("README.md")).toBe("md");
  });

  it("lowercases so matching is case-insensitive", () => {
    expect(extensionOf("README.MD")).toBe("md");
  });

  it("takes only the final extension", () => {
    expect(extensionOf("archive.tar.gz")).toBe("gz");
  });

  // A dotfile is named, not extended: ".gitignore" has no extension.
  it("does not treat a dotfile's name as an extension", () => {
    expect(extensionOf(".gitignore")).toBe("");
    expect(extensionOf("config/.env")).toBe("");
  });

  it("returns nothing when there is no extension", () => {
    expect(extensionOf("run")).toBe("");
    expect(extensionOf("")).toBe("");
    expect(extensionOf(null)).toBe("");
  });

  // A dot in a directory must not be mistaken for the file's extension.
  it("ignores dots in parent directories", () => {
    expect(extensionOf("my.dir/run")).toBe("");
  });
});
