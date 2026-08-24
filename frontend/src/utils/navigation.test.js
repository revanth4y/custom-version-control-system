import { describe, expect, it } from "vitest";

import { activeNavKey, navItemsFor } from "./navigation";

describe("global navigation", () => {
  describe("what is offered", () => {
    it("offers nothing to a visitor without an identity", () => {
      expect(navItemsFor(null)).toEqual([]);
      expect(navItemsFor(undefined)).toEqual([]);
      expect(navItemsFor({})).toEqual([]);
    });

    it("offers the dashboard and the viewer's own repositories", () => {
      const items = navItemsFor({ username: "revant" });

      expect(items.map((i) => i.key)).toEqual(["dashboard", "repositories"]);
      expect(items.map((i) => i.to)).toEqual(["/", "/revant"]);
    });

    it("points at the signed-in user, not a fixed path", () => {
      expect(navItemsFor({ username: "someone-else" })[1].to).toBe("/someone-else");
    });

    it("offers no entry that has nothing behind it", () => {
      const labels = navItemsFor({ username: "revant" }).map((i) => i.label);

      expect(labels).not.toContain("Explore");
      expect(labels).not.toContain("Docs");
    });
  });

  describe("which entry is active", () => {
    const items = navItemsFor({ username: "revant" });

    it("marks the dashboard only on the root path", () => {
      expect(activeNavKey(items, "/")).toBe("dashboard");
    });

    it("does not treat the dashboard as a prefix of every route", () => {
      expect(activeNavKey(items, "/revant")).toBe("repositories");
      expect(activeNavKey(items, "/someone/repo")).toBeNull();
    });

    it("stays active for pages underneath the entry", () => {
      expect(activeNavKey(items, "/revant/engine")).toBe("repositories");
      expect(activeNavKey(items, "/revant/engine/commits")).toBe("repositories");
    });

    it("matches on a segment boundary, not a bare prefix", () => {
      expect(activeNavKey(items, "/revanthy")).toBeNull();
      expect(activeNavKey(items, "/revanthy/repo")).toBeNull();
    });

    it("ignores a trailing slash", () => {
      expect(activeNavKey(items, "/revant/")).toBe("repositories");
      expect(activeNavKey(items, "//")).toBe("dashboard");
    });

    it("returns nothing for a route outside the navigation", () => {
      expect(activeNavKey(items, "/new")).toBeNull();
      expect(activeNavKey(items, "/login")).toBeNull();
    });

    it("returns nothing when there are no entries", () => {
      expect(activeNavKey([], "/")).toBeNull();
    });
  });
});
