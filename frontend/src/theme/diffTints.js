import { palette } from "./palette";

/**
 * Diff row colours, as explicit values rather than theme variables.
 *
 * The approved palette specifies exact backgrounds and text colours for added
 * and removed rows - #DCFCE7 on light, #0D2818 on dark and so on. Those are
 * opaque colours chosen for the purpose, not the generic success and danger
 * tints, so they are read straight from the palette rather than derived.
 *
 * `DiffLine` also needs them as concrete values because its line-number gutters
 * are sticky. A sticky cell must be fully opaque or the code scrolling beneath
 * shows through it, so the gutter paints this colour over the row background
 * itself - which needs a real value, not a CSS variable it cannot resolve at
 * that point.
 *
 * The scheme comes from the attribute the provider writes, so a component gets
 * the right colours without subscribing to anything.
 */
const activeScheme = () =>
  (typeof document !== "undefined" && document.documentElement.getAttribute("data-theme")) === "dark"
    ? "dark"
    : "light";

/** Background for an added or removed row. */
export const diffBackgroundFor = (kind, scheme = activeScheme()) => {
  const p = palette[scheme] ?? palette.dark;
  return kind === "added" ? p.diffAddBg : p.diffDelBg;
};

/** The sign and text colour that goes with it. */
export const diffForegroundFor = (kind, scheme = activeScheme()) => {
  const p = palette[scheme] ?? palette.dark;
  return kind === "added" ? p.diffAddFg : p.diffDelFg;
};
