import { describe, expect, it } from "vitest";

import {
  descriptionOf,
  isPrivate,
  repositoryCountLabel,
  repositoryPath,
  visibilityLabel,
} from "./repositories";

const repo = (overrides = {}) => ({
  id: "1",
  name: "engine",
  description: "A version control system.",
  visibility: "PUBLIC",
  ownerUsername: "revant",
  ...overrides,
});

describe("repository display", () => {
  describe("where a card links", () => {
    it("addresses a repository by owner and name", () => {
      expect(repositoryPath(repo())).toBe("/revant/engine");
    });

    it("uses the record's own owner, not the page's", () => {
      expect(repositoryPath(repo({ ownerUsername: "someone-else" }))).toBe("/someone-else/engine");
    });
  });

  describe("visibility", () => {
    it("reads PRIVATE as private", () => {
      expect(isPrivate(repo({ visibility: "PRIVATE" }))).toBe(true);
      expect(visibilityLabel(repo({ visibility: "PRIVATE" }))).toBe("Private");
    });

    it("reads PUBLIC as public", () => {
      expect(isPrivate(repo())).toBe(false);
      expect(visibilityLabel(repo())).toBe("Public");
    });

    it("labels every card, so a missing badge never has to be interpreted", () => {
      expect(visibilityLabel(repo())).toBeTruthy();
      expect(visibilityLabel(repo({ visibility: "PRIVATE" }))).toBeTruthy();
    });

    it("does not treat an unknown value as private", () => {
      expect(isPrivate(repo({ visibility: undefined }))).toBe(false);
      expect(isPrivate(undefined)).toBe(false);
    });
  });

  describe("counting", () => {
    it("says nothing definite before the count arrives", () => {
      expect(repositoryCountLabel(undefined)).toBe("counting repositories");
      expect(repositoryCountLabel(null)).toBe("counting repositories");
    });

    it("distinguishes an absent count from none", () => {
      expect(repositoryCountLabel(0)).toBe("0 repositories");
    });

    it("singularises exactly one", () => {
      expect(repositoryCountLabel(1)).toBe("1 repository");
      expect(repositoryCountLabel(2)).toBe("2 repositories");
      expect(repositoryCountLabel(7)).toBe("7 repositories");
    });
  });

  describe("description", () => {
    it("returns the text when there is one", () => {
      expect(descriptionOf(repo())).toBe("A version control system.");
    });

    it("treats null, empty and whitespace alike as absent", () => {
      expect(descriptionOf(repo({ description: null }))).toBeNull();
      expect(descriptionOf(repo({ description: "" }))).toBeNull();
      expect(descriptionOf(repo({ description: "   \n  " }))).toBeNull();
      expect(descriptionOf(repo({ description: undefined }))).toBeNull();
    });

    it("trims surrounding whitespace rather than rendering it", () => {
      expect(descriptionOf(repo({ description: "  spaced  " }))).toBe("spaced");
    });
  });
});
