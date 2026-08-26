import { describe, expect, it } from "vitest";

import {
  VISIBILITY_OPTIONS,
  confirmsDeletion,
  descriptionUpdate,
  hasChanged,
} from "./repositorySettings";
import { MAX_DESCRIPTION_LENGTH, validateRepositoryName } from "./repoName";

describe("repository settings", () => {
  describe("noticing a change", () => {
    it("sees an edit", () => {
      expect(hasChanged("old", "new")).toBe(true);
    });

    it("does not call an unedited field changed", () => {
      expect(hasChanged("same", "same")).toBe(false);
    });

    it("ignores whitespace either side", () => {
      // Otherwise the save button arms itself when the cursor lands in the field.
      expect(hasChanged("same", "  same  ")).toBe(false);
    });

    it("treats an absent value as empty", () => {
      /* A repository with no description has null, not "". Reading those as
         different states would arm the button on a field nobody touched. */
      expect(hasChanged(null, "")).toBe(false);
      expect(hasChanged(undefined, "")).toBe(false);
      expect(hasChanged(null, "something")).toBe(true);
    });

    it("sees a description being cleared", () => {
      expect(hasChanged("had one", "")).toBe(true);
    });
  });

  describe("what to send for a description", () => {
    it("sends nothing when nothing changed", () => {
      expect(descriptionUpdate("same", "same")).toBeNull();
    });

    it("sends the new text", () => {
      expect(descriptionUpdate("old", "new")).toEqual({ description: "new" });
    });

    it("sends an empty string to clear it, rather than omitting the field", () => {
      /* The distinction the server draws: an absent field means "leave it", an
         empty one means "clear it". Omitting it here would look like a save
         that worked and change nothing. */
      expect(descriptionUpdate("had one", "")).toEqual({ description: "" });
    });

    it("clears a description that is only whitespace", () => {
      expect(descriptionUpdate("had one", "   ")).toEqual({ description: "" });
    });

    it("trims what it sends", () => {
      expect(descriptionUpdate("old", "  new  ")).toEqual({ description: "new" });
    });
  });

  describe("authorising a deletion", () => {
    it("accepts the exact name", () => {
      expect(confirmsDeletion("portfolio", "portfolio")).toBe(true);
    });

    it("refuses a different case", () => {
      // The point of typing it is that it cannot happen by accident; matching
      // loosely gives most of that away.
      expect(confirmsDeletion("portfolio", "Portfolio")).toBe(false);
      expect(confirmsDeletion("portfolio", "PORTFOLIO")).toBe(false);
    });

    it("refuses a near miss", () => {
      expect(confirmsDeletion("portfolio", "portfoli")).toBe(false);
      expect(confirmsDeletion("portfolio", "portfolios")).toBe(false);
      expect(confirmsDeletion("portfolio", "port folio")).toBe(false);
    });

    it("refuses nothing at all", () => {
      expect(confirmsDeletion("portfolio", "")).toBe(false);
      expect(confirmsDeletion("portfolio", null)).toBe(false);
      expect(confirmsDeletion("portfolio", undefined)).toBe(false);
    });

    it("forgives whitespace around a pasted name", () => {
      expect(confirmsDeletion("portfolio", "  portfolio  ")).toBe(true);
    });

    it("cannot be satisfied when there is no name to match", () => {
      // A repository still loading has no name; nothing typed should arm the
      // button, least of all an empty box matching an empty name.
      expect(confirmsDeletion("", "")).toBe(false);
      expect(confirmsDeletion(null, "")).toBe(false);
      expect(confirmsDeletion(undefined, "anything")).toBe(false);
    });
  });

  describe("the rules it borrows rather than restates", () => {
    it("validates a new name with the same function the create form uses", () => {
      /* One definition of a legal name. A second copy here would pass its own
         tests while disagreeing with the server. */
      expect(validateRepositoryName("has spaces")).not.toBeNull();
      expect(validateRepositoryName("-leading-hyphen")).not.toBeNull();
      expect(validateRepositoryName("")).not.toBeNull();
      expect(validateRepositoryName("perfectly.fine_name-2")).toBeNull();
    });

    it("bounds the description where the server does, from one definition", () => {
      /* Deliberately imported from repoName rather than restated: the create
         form already had this number, and two copies is how they drift. */
      expect(MAX_DESCRIPTION_LENGTH).toBe(500);
    });
  });

  describe("the visibility choices", () => {
    it("offers exactly public and private", () => {
      expect(VISIBILITY_OPTIONS.map((o) => o.value)).toEqual(["PUBLIC", "PRIVATE"]);
    });

    it("says who can read, not just which word applies", () => {
      const [publicOption, privateOption] = VISIBILITY_OPTIONS;

      expect(publicOption.description).toBe("Anyone can view this repository.");
      expect(privateOption.description).toBe(
        "Only you and authorized users can view this repository.",
      );
    });
  });
});
