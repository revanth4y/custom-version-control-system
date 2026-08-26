import { describe, expect, it, vi } from "vitest";

import {
  INTENSITY_LEVELS,
  busiestDay,
  describeDay,
  intensityOf,
  monthLabels,
  toWeeks,
  weekdayOf,
} from "./contributions";

/** A run of days starting on a known date, so weekday alignment is checkable. */
const range = (startIso, length, counts = {}) => {
  const [y, m, d] = startIso.split("-").map(Number);
  const days = [];
  for (let i = 0; i < length; i += 1) {
    const date = new Date(Date.UTC(y, m - 1, d + i));
    const iso = date.toISOString().slice(0, 10);
    days.push({ date: iso, count: counts[iso] ?? 0 });
  }
  return days;
};

describe("intensityOf", () => {
  it("is zero only when there were no commits", () => {
    expect(intensityOf(0, 10)).toBe(0);
    expect(intensityOf(null, 10)).toBe(0);
  });

  // Real work must never be drawn as absence, however quiet the day.
  it("never renders a day with commits as empty", () => {
    expect(intensityOf(1, 1000)).toBe(1);
    expect(intensityOf(1, 4)).toBeGreaterThanOrEqual(1);
  });

  it("puts the busiest day at the top of the scale", () => {
    expect(intensityOf(10, 10)).toBe(INTENSITY_LEVELS - 1);
  });

  it("scales relative to the busiest day, not to fixed counts", () => {
    // The same count means different things in a quiet and a busy window.
    expect(intensityOf(2, 2)).toBe(4);
    expect(intensityOf(2, 100)).toBe(1);
  });

  it("stays within the scale", () => {
    for (const [count, max] of [[1, 1], [7, 9], [50, 51], [1, 2], [99, 100]]) {
      const level = intensityOf(count, max);
      expect(level).toBeGreaterThanOrEqual(1);
      expect(level).toBeLessThanOrEqual(INTENSITY_LEVELS - 1);
    }
  });

  it("survives a window with no activity at all", () => {
    expect(intensityOf(0, 0)).toBe(0);
    expect(intensityOf(3, 0)).toBe(0);
  });
});

describe("busiestDay", () => {
  it("finds the maximum", () => {
    expect(busiestDay([{ count: 0 }, { count: 7 }, { count: 3 }])).toBe(7);
  });

  it("is zero for an empty or absent window", () => {
    expect(busiestDay([])).toBe(0);
    expect(busiestDay(undefined)).toBe(0);
  });
});

describe("weekdayOf", () => {
  // 2026-08-21 is a Friday; UTC is used because the server buckets by UTC day.
  it("reads the UTC weekday", () => {
    expect(weekdayOf("2026-08-21")).toBe(5);
    expect(weekdayOf("2026-08-23")).toBe(0);
  });
});

describe("toWeeks", () => {
  it("pads the first week so every row is one weekday", () => {
    // Starts on a Wednesday (3), so three leading cells must be empty.
    const weeks = toWeeks(range("2026-08-19", 10));
    expect(weeks[0].slice(0, 3)).toEqual([null, null, null]);
    expect(weeks[0][3].date).toBe("2026-08-19");
  });

  it("pads the last week too, so every column is full height", () => {
    const weeks = toWeeks(range("2026-08-19", 10));
    expect(weeks.every((week) => week.length === 7)).toBe(true);
    expect(weeks[weeks.length - 1].some((cell) => cell === null)).toBe(true);
  });

  it("keeps every day it was given, in order", () => {
    const days = range("2026-01-01", 365);
    const flattened = toWeeks(days).flat().filter(Boolean);
    expect(flattened).toHaveLength(365);
    expect(flattened.map((d) => d.date)).toEqual(days.map((d) => d.date));
  });

  it("invents no days of its own", () => {
    const days = range("2026-08-19", 10);
    const given = new Set(days.map((d) => d.date));
    for (const cell of toWeeks(days).flat().filter(Boolean)) {
      expect(given.has(cell.date)).toBe(true);
    }
  });

  it("lays a full year into 53 or 54 columns", () => {
    const weeks = toWeeks(range("2025-08-22", 365));
    expect(weeks.length).toBeGreaterThanOrEqual(53);
    expect(weeks.length).toBeLessThanOrEqual(54);
  });

  it("handles an empty range", () => {
    expect(toWeeks([])).toEqual([]);
    expect(toWeeks(undefined)).toEqual([]);
  });
});

describe("monthLabels", () => {
  const weeks = toWeeks(range("2025-08-22", 365));

  it("labels each month once, in order", () => {
    const labels = monthLabels(weeks);
    expect(labels.length).toBeGreaterThanOrEqual(11);
    expect(new Set(labels.map((l) => l.index)).size).toBe(labels.length);
    expect(labels.map((l) => l.index)).toEqual([...labels.map((l) => l.index)].sort((a, b) => a - b));
  });

  it("puts no label in the final column, which has no room to print it", () => {
    for (const label of monthLabels(weeks)) {
      expect(label.index).toBeLessThan(weeks.length - 1);
    }
  });

  it("handles an empty calendar", () => {
    expect(monthLabels([])).toEqual([]);
    expect(monthLabels(undefined)).toEqual([]);
  });
});

/**
 * Runs `read` with the ambient locale pinned, and puts it back afterwards.
 *
 * `describeDay` formats in whatever locale the reader has, which is right for
 * the page and useless to assert against: the same call renders "14 March
 * 2026" here and "March 14, 2026" on an en-US machine, so a test that names
 * either one passes in one place and fails in the other. Pinning it for the
 * length of one assertion fixes the wording under test without fixing it for
 * the application, which still formats dates the reader's way.
 *
 * en-US rather than the local rendering because every build of Node can
 * produce it, including the ones compiled without the full locale data.
 */
const withPinnedLocale = (locale, read) => {
  const format = Date.prototype.toLocaleDateString;
  const pinned = vi
    .spyOn(Date.prototype, "toLocaleDateString")
    .mockImplementation(function pinnedFormat(requested, options) {
      return format.call(this, requested ?? locale, options);
    });

  try {
    return read();
  } finally {
    pinned.mockRestore();
  }
};

describe("describeDay", () => {
  it("says the count and the date in words", () => {
    const said = withPinnedLocale("en-US", () => describeDay({ date: "2026-03-14", count: 3 }));

    expect(said).toBe("3 commits on March 14, 2026");
  });

  it("uses the singular for one", () => {
    expect(describeDay({ date: "2026-03-14", count: 1 })).toMatch(/^1 commit on /);
  });

  it("says so plainly when there were none", () => {
    expect(describeDay({ date: "2026-03-14", count: 0 })).toMatch(/^No commits on /);
  });

  it("is empty for a padding cell", () => {
    expect(describeDay(null)).toBe("");
  });
});
