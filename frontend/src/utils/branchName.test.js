import { describe, expect, it } from "vitest";

import { MAX_BRANCH_NAME_LENGTH, validateBranchName } from "./branchName";

describe("validateBranchName", () => {
  it("accepts ordinary names", () => {
    expect(validateBranchName("main")).toBeNull();
    expect(validateBranchName("release-1.0")).toBeNull();
    expect(validateBranchName("v2_final")).toBeNull();
  });

  // Slashes are hierarchy separators, not path escapes; these are the names the
  // query-parameter ref strategy exists to carry.
  it("accepts slash-separated names at any depth", () => {
    expect(validateBranchName("feature/login")).toBeNull();
    expect(validateBranchName("release/1.0")).toBeNull();
    expect(validateBranchName("bugfix/auth/token")).toBeNull();
  });

  it("requires a name", () => {
    expect(validateBranchName("")).toMatch(/required/i);
    expect(validateBranchName("   ")).toMatch(/required/i);
    expect(validateBranchName(null)).toMatch(/required/i);
    expect(validateBranchName(undefined)).toMatch(/required/i);
  });

  it("reserves HEAD", () => {
    expect(validateBranchName("HEAD")).toMatch(/reserved/i);
    // Only the exact name is reserved.
    expect(validateBranchName("HEADer")).toBeNull();
    expect(validateBranchName("head")).toBeNull();
  });

  // The reason the rules exist: a name is a path under refs/heads.
  it("rejects names that would escape the refs directory", () => {
    expect(validateBranchName("../../escape")).not.toBeNull();
    expect(validateBranchName("..")).not.toBeNull();
    expect(validateBranchName(".")).not.toBeNull();
    expect(validateBranchName("feature/../../etc")).not.toBeNull();
    expect(validateBranchName("C:\\work")).not.toBeNull();
  });

  it("rejects malformed slash usage", () => {
    expect(validateBranchName("/leading")).toMatch(/start or end/i);
    expect(validateBranchName("trailing/")).toMatch(/start or end/i);
    expect(validateBranchName("double//segment")).toMatch(/empty segment/i);
  });

  it("rejects characters with revision-syntax meaning", () => {
    for (const bad of ["a~b", "a^b", "a:b", "a?b", "a*b", "a[b", "a]b", "a\b", 'a"b', "a'b", "a<b", "a>b", "a|b", "a b"]) {
      expect(validateBranchName(bad), bad).not.toBeNull();
    }
  });

  it("rejects the reflog selector syntax", () => {
    expect(validateBranchName("main@{yesterday}")).toMatch(/@\{/);
  });

  it("rejects control characters", () => {
    expect(validateBranchName("a\u0000b")).toMatch(/control/i);
    expect(validateBranchName("a\nb")).toMatch(/control/i);
    expect(validateBranchName("a\u007fb")).toMatch(/control/i);
  });

  it("rejects segments with a leading dot or dash, or a .lock suffix", () => {
    expect(validateBranchName(".hidden")).toMatch(/start with '\.'/i);
    expect(validateBranchName("feature/.hidden")).toMatch(/start with '\.'/i);
    expect(validateBranchName("-dash")).toMatch(/start with '-'/i);
    expect(validateBranchName("feature/-dash")).toMatch(/start with '-'/i);
    expect(validateBranchName("main.lock")).toMatch(/\.lock/i);
    expect(validateBranchName("feature/main.lock")).toMatch(/\.lock/i);
    // A dot or dash inside a segment is fine.
    expect(validateBranchName("re.lease")).toBeNull();
    expect(validateBranchName("re-lease")).toBeNull();
  });

  it("bounds the length", () => {
    expect(validateBranchName("a".repeat(MAX_BRANCH_NAME_LENGTH))).toBeNull();
    expect(validateBranchName("a".repeat(MAX_BRANCH_NAME_LENGTH + 1))).toMatch(/characters/i);
  });
});
