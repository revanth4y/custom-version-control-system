import { describe, expect, it } from "vitest";

import { MAX_NAME_LENGTH, validateRepositoryName } from "./repoName";

describe("validateRepositoryName", () => {
  it("accepts ordinary names", () => {
    expect(validateRepositoryName("gitforge-engine")).toBeNull();
    expect(validateRepositoryName("my_project.v2")).toBeNull();
    expect(validateRepositoryName("2048")).toBeNull();
  });

  it("requires a name", () => {
    expect(validateRepositoryName("")).toMatch(/required/i);
    expect(validateRepositoryName(null)).toMatch(/required/i);
    expect(validateRepositoryName(undefined)).toMatch(/required/i);
  });

  // A name of spaces is empty once trimmed, so it must fail as missing rather
  // than as a character-set violation.
  it("treats whitespace as no name at all", () => {
    expect(validateRepositoryName("   ")).toMatch(/required/i);
  });

  it("rejects a leading separator", () => {
    expect(validateRepositoryName("-leading")).not.toBeNull();
    expect(validateRepositoryName(".hidden")).not.toBeNull();
    expect(validateRepositoryName("_private")).not.toBeNull();
  });

  it("rejects characters a path or URL would have to escape", () => {
    expect(validateRepositoryName("has space")).not.toBeNull();
    expect(validateRepositoryName("with/slash")).not.toBeNull();
    expect(validateRepositoryName("q?uery")).not.toBeNull();
    expect(validateRepositoryName("emoji-\u{1F600}")).not.toBeNull();
  });

  it("bounds the length", () => {
    expect(validateRepositoryName("a".repeat(MAX_NAME_LENGTH))).toBeNull();
    expect(validateRepositoryName("a".repeat(MAX_NAME_LENGTH + 1))).toMatch(/characters/i);
  });

  it("ignores surrounding whitespace when judging a valid name", () => {
    expect(validateRepositoryName("  spaced-out  ")).toBeNull();
  });
});
