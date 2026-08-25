import { describe, expect, it } from "vitest";

import {
  headResolved,
  isEmptyRepository,
  repositoryKey,
  shouldLoadTree,
} from "./repositoryState";

const head = (commit) => ({ branch: "main", commit, detached: false });

describe("repository loading state", () => {
  describe("telling 'not yet known' apart from 'empty'", () => {
    it("treats an absent HEAD as unresolved", () => {
      expect(headResolved(null)).toBe(false);
      expect(headResolved(undefined)).toBe(false);
    });

    it("treats an answered HEAD as resolved, commits or not", () => {
      expect(headResolved(head("abc123"))).toBe(true);
      expect(headResolved(head(null))).toBe(true);
    });

    it("does not call a still-loading repository empty", () => {
      /* The distinction the whole fix rests on: while HEAD is in flight the
         honest answer is "unknown", not "empty". */
      expect(isEmptyRepository(null)).toBe(false);
      expect(isEmptyRepository(undefined)).toBe(false);
    });

    it("calls a repository empty once HEAD says it has no commit", () => {
      expect(isEmptyRepository(head(null))).toBe(true);
    });

    it("does not call a repository with a commit empty", () => {
      expect(isEmptyRepository(head("abc123"))).toBe(false);
    });
  });

  describe("whether to request the file listing", () => {
    it("waits while HEAD is still in flight", () => {
      expect(shouldLoadTree(null)).toBe(false);
      expect(shouldLoadTree(undefined)).toBe(false);
    });

    it("does not ask for a tree that cannot exist", () => {
      /* Without a commit there is no directory to list; the server answers 404,
         correctly. HEAD already told us, so asking learns nothing. */
      expect(shouldLoadTree(head(null))).toBe(false);
    });

    it("asks once there is a commit to list", () => {
      expect(shouldLoadTree(head("abc123"))).toBe(true);
    });

    it("is the exact inverse of empty, once HEAD has resolved", () => {
      for (const h of [head(null), head("abc123")]) {
        expect(shouldLoadTree(h)).toBe(!isEmptyRepository(h));
      }
    });

    it("is false for both, while HEAD is unresolved", () => {
      /* Neither "load the tree" nor "show the empty state" is correct yet. */
      expect(shouldLoadTree(null)).toBe(false);
      expect(isEmptyRepository(null)).toBe(false);
    });
  });

  describe("repository identity", () => {
    it("distinguishes two repositories", () => {
      expect(repositoryKey("forge-demo", "engine")).not.toBe(
        repositoryKey("forge-demo", "no-history"),
      );
    });

    it("distinguishes the same name under different owners", () => {
      expect(repositoryKey("alice", "engine")).not.toBe(repositoryKey("bob", "engine"));
    });

    it("is stable for the same repository", () => {
      expect(repositoryKey("forge-demo", "engine")).toBe(repositoryKey("forge-demo", "engine"));
    });

    it("does not collide when a segment is missing", () => {
      /* A key that collapsed to the same string for different inputs would fail
         to remount, which is the bug this key exists to prevent. */
      expect(repositoryKey("a/b", undefined)).not.toBe(repositoryKey("a", "b"));
      expect(repositoryKey(undefined, undefined)).toBe("/");
    });
  });
});
