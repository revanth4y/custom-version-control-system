import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { formatAbsoluteTime, formatRelativeTime } from "./dates";

describe("formatRelativeTime", () => {
  const now = new Date("2026-08-20T12:00:00Z");

  beforeEach(() => {
    // The function reads the wall clock, so it is pinned rather than the test
    // being written to tolerate whatever "now" happens to be.
    vi.useFakeTimers();
    vi.setSystemTime(now);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  const ago = (seconds) => new Date(now.getTime() - seconds * 1000).toISOString();

  it("describes the last minute as just now", () => {
    expect(formatRelativeTime(ago(0))).toBe("just now");
    expect(formatRelativeTime(ago(44))).toBe("just now");
  });

  it("counts minutes", () => {
    expect(formatRelativeTime(ago(60))).toBe("1 minute ago");
    expect(formatRelativeTime(ago(60 * 5))).toBe("5 minutes ago");
  });

  it("counts hours", () => {
    expect(formatRelativeTime(ago(3600))).toBe("1 hour ago");
    expect(formatRelativeTime(ago(3600 * 5))).toBe("5 hours ago");
  });

  it("counts days", () => {
    expect(formatRelativeTime(ago(86400))).toBe("1 day ago");
    expect(formatRelativeTime(ago(86400 * 10))).toBe("10 days ago");
  });

  it("counts months and years", () => {
    expect(formatRelativeTime(ago(86400 * 60))).toBe("2 months ago");
    expect(formatRelativeTime(ago(86400 * 400))).toBe("1 year ago");
  });

  it("singularises exactly one unit", () => {
    // "1 minutes ago" is the kind of detail that quietly makes a page look unfinished.
    expect(formatRelativeTime(ago(60))).not.toContain("minutes");
    expect(formatRelativeTime(ago(86400))).not.toContain("days");
  });

  it("does not crash on missing or malformed input", () => {
    // Timestamps come from the API; a page must not blank out because one is odd.
    expect(formatRelativeTime(null)).toBe("unknown");
    expect(formatRelativeTime(undefined)).toBe("unknown");
    expect(formatRelativeTime("not a date")).toBe("unknown");
  });
});

describe("formatAbsoluteTime", () => {
  it("returns a readable string for a valid timestamp", () => {
    expect(formatAbsoluteTime("2026-08-20T12:00:00Z")).not.toBe("");
  });

  it("returns an empty string rather than 'Invalid Date'", () => {
    expect(formatAbsoluteTime(null)).toBe("");
    expect(formatAbsoluteTime("nonsense")).toBe("");
  });
});
