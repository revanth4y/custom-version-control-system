/**
 * The approved GitForge V2 palette.
 *
 * These values are the source of truth and are not to be changed without
 * approval. Every pair is measured against WCAG AA in `palette.test.js`, which
 * fails the build if a token is edited below threshold — the report that
 * produced these numbers is a test, not a document that goes stale.
 *
 * Two themes, one brand. `accent` is the brand green and is the same in both.
 * What differs is `accentText`: #22C55E is a bright green tuned for dark
 * surfaces and measures 2.18:1 on the light canvas, far below the 4.5:1 text
 * needs, so light-mode text and links use the darker step of the same ramp.
 * Fills stay brand green; only text darkens.
 */

/** Backgrounds, foregrounds and semantics, per theme. */
export const palette = {
  light: {
    canvas: "#F9FAFB",
    surface: "#FFFFFF",
    /* One step below the surface, for wells and code blocks. */
    inset: "#F3F4F6",

    fg: "#111827",
    fgMuted: "#6B7280",

    /* Dividers and card edges. Decorative: no contrast requirement. */
    border: "#E5E7EB",
    borderMuted: "#F0F1F3",
    /* Input boundaries. These identify a control, so they must reach 3:1. */
    controlBorder: "#6B7280",

    accentText: "#15803D",
    buttonBg: "#15803D",
    buttonText: "#FFFFFF",
    buttonHoverBg: "#166534",
    buttonActiveBg: "#14532D",

    success: "#15803D",
    warning: "#B45309",
    error: "#DC2626",
    info: "#2563EB",

    diffAddBg: "#DCFCE7",
    diffAddFg: "#166534",
    diffDelBg: "#FEE2E2",
    diffDelFg: "#991B1B",

    /* Tint strength for subtle backgrounds, composited over the canvas. */
    tintAlpha: 0.12,
  },

  dark: {
    canvas: "#0D1117",
    surface: "#161B22",
    inset: "#0A0E13",

    fg: "#E6EDF3",
    fgMuted: "#8B949E",

    border: "#30363D",
    borderMuted: "#21262D",
    controlBorder: "#6B7280",

    accentText: "#22C55E",
    buttonBg: "#22C55E",
    /* Not white: white on the brand green measures 2.28:1 in either theme. */
    buttonText: "#0D1117",
    buttonHoverBg: "#16A34A",
    /* Pressed is the approved hover value, not a darker one. In dark mode the
       label is dark, so darkening the fill lowers contrast rather than raising
       it - the ramp runs out at #16A34A (5.74:1) and #15803D would be 3.77:1.
       Depth is expressed by the pressed border instead of by hue. */
    buttonActiveBg: "#16A34A",

    success: "#22C55E",
    warning: "#F59E0B",
    error: "#EF4444",
    info: "#3B82F6",

    diffAddBg: "#0D2818",
    diffAddFg: "#4ADE80",
    diffDelBg: "#2D0F0F",
    diffDelFg: "#F87171",

    tintAlpha: 0.16,
  },
};

/** The brand green. One value, both themes, every fill. */
export const brand = {
  accent: "#22C55E",
  accentHover: "#16A34A",
};

/**
 * The terminal surface, fixed in both themes.
 *
 * A terminal is black because a terminal is black; recolouring it in light mode
 * would make it read as a styled panel rather than a console.
 */
export const terminal = "#0D1117";

/**
 * Commit graph lanes, per theme.
 *
 * Hue identity is preserved across themes — lane 3 is amber in both — while the
 * light set uses darker steps so every lane clears 3:1 against its own canvas.
 * Lanes 1-3 reuse the semantic colours, so the graph speaks the same language
 * as the rest of the interface.
 *
 * Green appears once, on the trunk. The remaining lanes are deliberately not
 * green: a graph where every line is the brand colour cannot be read.
 *
 * The light set is close in luminance across hues, which colour alone would not
 * separate for a red-green colour-blind reader. That is acceptable here because
 * colour is redundant: `graphMetrics.laneX` gives every lane its own horizontal
 * position, so parentage is traceable without relying on hue at all.
 */
export const laneColors = {
  light: ["#15803D", "#2563EB", "#B45309", "#7E22CE", "#0F766E", "#BE185D"],
  dark: ["#22C55E", "#3B82F6", "#F59E0B", "#A97BFF", "#00B4AB", "#F34B7D"],
};

/**
 * Language colours, identical in both themes.
 *
 * Fixed by convention rather than by our palette — a reader recognises Java's
 * brown and Go's cyan, and theming them would break that recognition. They are
 * only ever drawn as a swatch beside a text label naming the language, so they
 * carry no information on their own and need no contrast guarantee; the swatch
 * takes a hairline border so pale ones keep an edge on white.
 */
export const languageColors = {
  Java: "#B07219",
  JavaScript: "#F1E05A",
  TypeScript: "#3178C6",
  Python: "#3572A5",
  HTML: "#E34C26",
  CSS: "#563D7C",
  C: "#555555",
  "C++": "#F34B7D",
  "C#": "#178600",
  Go: "#00ADD8",
  Rust: "#DEA584",
  PHP: "#4F5D95",
  Ruby: "#701516",
  Shell: "#89E051",
  SQL: "#E38C00",
  Dockerfile: "#384D54",
  YAML: "#CB171E",
  Markdown: "#083FA1",
  Kotlin: "#A97BFF",
  Swift: "#F05138",
  Dart: "#00B4AB",
  Vue: "#41B883",
  JSX: "#F1E05A",
  Scala: "#C22D40",
  R: "#198CE7",
  Perl: "#0298C3",
  Haskell: "#5E5086",
  Elixir: "#6E4A7E",
  Lua: "#000080",
  "Objective-C": "#438EFF",
};

export const fonts = {
  /* Inter and JetBrains Mono are self-hosted; the stacks behind them are the
     fallback for the moment before the woff2 arrives, not a substitute. */
  body: '"Inter Variable", Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif',
  mono: '"JetBrains Mono Variable", "JetBrains Mono", "Fira Code", ui-monospace, SFMono-Regular, Menlo, Consolas, "Liberation Mono", monospace',
};

/** Corner radii. The references round cards more generously than Primer does. */
export const radii = { sm: "4px", md: "8px", lg: "10px" };

/** Adds an alpha channel to a hex colour, for tints laid over a canvas. */
export const withAlpha = (hex, alpha) => {
  const value = hex.replace("#", "");
  const r = parseInt(value.slice(0, 2), 16);
  const g = parseInt(value.slice(2, 4), 16);
  const b = parseInt(value.slice(4, 6), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
};
