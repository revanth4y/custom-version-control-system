import { describe, expect, it } from "vitest";

import { avatarFor } from "./avatar";

describe("avatarFor", () => {
  const decode = (dataUri) => decodeURIComponent(dataUri.replace("data:image/svg+xml;utf8,", ""));

  it("produces an inline SVG data URI, so nothing is fetched", () => {
    // The point of generating these is that a page never depends on a third
    // party for an image. The xmlns declaration inside the SVG is a namespace
    // identifier, not a request, so what matters is that there is no reference
    // the browser would actually resolve.
    const uri = avatarFor("ada");
    const svg = decode(uri);

    expect(uri.startsWith("data:image/svg+xml")).toBe(true);
    expect(svg).not.toMatch(/(href|src)\s*=/);
    expect(svg).not.toContain("//avatars.");
  });

  it("is stable for the same username", () => {
    expect(avatarFor("ada")).toBe(avatarFor("ada"));
  });

  it("differs between usernames", () => {
    expect(avatarFor("ada")).not.toBe(avatarFor("grace"));
  });

  it("shows the first character, uppercased", () => {
    expect(decode(avatarFor("ada"))).toContain(">A<");
    expect(decode(avatarFor("Zoe"))).toContain(">Z<");
  });

  it("escapes characters that would otherwise break the markup", () => {
    // The initial comes from user-supplied data, so it cannot be interpolated raw.
    const svg = decode(avatarFor("<script>"));

    expect(svg).toContain("&lt;");
    expect(svg).not.toContain("><script>");
  });

  it("falls back to a placeholder for an empty or missing name", () => {
    expect(decode(avatarFor(""))).toContain(">?<");
    expect(decode(avatarFor(undefined))).toContain(">?<");
    expect(decode(avatarFor("   "))).toContain(">?<");
  });
});
