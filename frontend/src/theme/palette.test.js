import { describe, expect, it } from "vitest";

import { AA_LARGE, AA_TEXT, composite, contrast, parseColor } from "./contrast";
import { brand, laneColors, languageColors, palette, withAlpha } from "./palette";

/**
 * The accessibility audit, as a test.
 *
 * A report saying the palette passed goes stale the moment someone nudges a
 * colour. This does not: edit a token below its threshold and the build fails,
 * naming the pair. That is the whole reason it exists.
 */

const themes = [
  ["light", palette.light],
  ["dark", palette.dark],
];

describe("contrast maths", () => {
  it("composites a translucent colour before measuring it", () => {
    // The V1 bug: measuring rgba(...) as though it were opaque. Half black over
    // white is mid grey, not black.
    expect(composite("rgba(0, 0, 0, 0.5)", "#FFFFFF")).toEqual({ r: 128, g: 128, b: 128, a: 1 });
  });

  it("treats a fully transparent colour as its backdrop", () => {
    expect(contrast("#FFFFFF", "rgba(0, 0, 0, 0)", "#FFFFFF")).toBeCloseTo(1, 5);
  });

  it("agrees with the published extremes", () => {
    expect(contrast("#000000", "#FFFFFF")).toBeCloseTo(21, 1);
    expect(contrast("#FFFFFF", "#FFFFFF")).toBeCloseTo(1, 5);
  });

  it("is symmetric in its arguments", () => {
    expect(contrast("#15803D", "#FFFFFF")).toBeCloseTo(contrast("#FFFFFF", "#15803D"), 5);
  });

  it("reads both hex and rgba", () => {
    expect(parseColor("#22C55E")).toEqual({ r: 34, g: 197, b: 94, a: 1 });
    expect(parseColor("rgba(34, 197, 94, 0.16)")).toEqual({ r: 34, g: 197, b: 94, a: 0.16 });
  });
});

describe.each(themes)("%s theme meets WCAG AA", (name, p) => {
  const surfaces = [
    ["canvas", p.canvas],
    ["surface", p.surface],
  ];

  it.each(surfaces)("body text on %s", (_, background) => {
    expect(contrast(p.fg, background)).toBeGreaterThanOrEqual(AA_TEXT);
  });

  it.each(surfaces)("secondary text on %s", (_, background) => {
    expect(contrast(p.fgMuted, background)).toBeGreaterThanOrEqual(AA_TEXT);
  });

  it.each(surfaces)("links and accent text on %s", (_, background) => {
    expect(contrast(p.accentText, background)).toBeGreaterThanOrEqual(AA_TEXT);
  });

  it.each([
    ["success", "success"],
    ["warning", "warning"],
    ["error", "error"],
    ["info", "info"],
  ])("%s text on the surface", (_, key) => {
    expect(contrast(p[key], p.surface)).toBeGreaterThanOrEqual(AA_TEXT);
  });

  it("the primary button label reads against its fill, hover and active states", () => {
    expect(contrast(p.buttonText, p.buttonBg)).toBeGreaterThanOrEqual(AA_TEXT);
    expect(contrast(p.buttonText, p.buttonHoverBg)).toBeGreaterThanOrEqual(AA_TEXT);
    expect(contrast(p.buttonText, p.buttonActiveBg)).toBeGreaterThanOrEqual(AA_TEXT);
  });

  it("diff text reads against its own row background", () => {
    expect(contrast(p.diffAddFg, p.diffAddBg)).toBeGreaterThanOrEqual(AA_TEXT);
    expect(contrast(p.diffDelFg, p.diffDelBg)).toBeGreaterThanOrEqual(AA_TEXT);
  });

  it("body text survives being laid over a diff row", () => {
    expect(contrast(p.fg, p.diffAddBg)).toBeGreaterThanOrEqual(AA_TEXT);
    expect(contrast(p.fg, p.diffDelBg)).toBeGreaterThanOrEqual(AA_TEXT);
  });

  it("body text survives being laid over a translucent tint", () => {
    // The pairs V1 measured wrongly, composited properly here.
    const tint = withAlpha(brand.accent, p.tintAlpha);
    expect(contrast(p.fg, tint, p.canvas)).toBeGreaterThanOrEqual(AA_TEXT);
  });

  it.each(surfaces)("a control boundary is identifiable against %s", (_, background) => {
    // Inputs need 3:1 to be found at all. Decorative dividers deliberately do
    // not, and are not asserted here.
    expect(contrast(p.controlBorder, background)).toBeGreaterThanOrEqual(AA_LARGE);
  });

  it.each(surfaces)("the focus ring is visible against %s", (_, background) => {
    expect(contrast(p.accentText, background)).toBeGreaterThanOrEqual(AA_LARGE);
  });
});

describe("the brand green", () => {
  it("is one value across both themes", () => {
    expect(palette.light.buttonBg === brand.accent || palette.dark.buttonBg === brand.accent).toBe(true);
    expect(palette.dark.accentText).toBe(brand.accent);
  });

  it("cannot carry a white label, which is why light darkens the fill", () => {
    // Documents the constraint that produced the two button treatments; if this
    // ever stops being true the light fill can be simplified.
    expect(contrast("#FFFFFF", brand.accent)).toBeLessThan(AA_TEXT);
  });
});

describe("commit graph lanes", () => {
  it.each(themes)("every %s lane is visible against its own canvas", (name, p) => {
    const lanes = laneColors[name];
    lanes.forEach((lane) => {
      expect(contrast(lane, p.canvas)).toBeGreaterThanOrEqual(AA_LARGE);
    });
  });

  it("keeps hue identity across themes by using the same number of lanes", () => {
    expect(laneColors.light).toHaveLength(laneColors.dark.length);
  });

  it("uses the brand green once, on the trunk", () => {
    // A graph where every line is the brand colour cannot be read.
    expect(laneColors.dark[0]).toBe(brand.accent);
    expect(laneColors.dark.filter((lane) => lane === brand.accent)).toHaveLength(1);
  });
});

describe("language colours", () => {
  it("are the same in both themes", () => {
    // They are recognised by convention; theming them would break recognition.
    expect(languageColors.Java).toBe("#B07219");
    expect(languageColors.Go).toBe("#00ADD8");
  });

  it("are all valid hex", () => {
    Object.entries(languageColors).forEach(([, value]) => {
      expect(value).toMatch(/^#[0-9A-F]{6}$/i);
    });
  });
});
