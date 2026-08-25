import { theme as primerTheme } from "@primer/react";

import { brand, fonts, laneColors, palette, radii, withAlpha } from "./palette";

export { brand, fonts, laneColors, languageColors, palette, radii, terminal, withAlpha } from "./palette";

/**
 * GitForge design tokens over Primer.
 *
 * Two real colour schemes now. V1 mapped `light`, `dark` and `dark_dimmed` to
 * the same dark object, so a system preference for light rendered a dark page;
 * each is built properly here from {@link palette}.
 *
 * Only the variables Primer actually reads are replaced, so components keep
 * their built-in behaviour — focus rings, disabled states, elevation — while
 * taking on this palette. That is what lets a retheme of this size touch one
 * file: roughly 370 usages across the application name Primer variables rather
 * than colours.
 */
const schemeFrom = (p, base) => ({
  ...base,
  colors: {
    ...base.colors,

    canvasDefault: p.canvas,
    canvasOverlay: p.surface,
    canvasInset: p.inset,
    canvasSubtle: p.surface,

    fgDefault: p.fg,
    fgMuted: p.fgMuted,
    /* V1 had a third, dimmer foreground that measured 3.99:1 — below AA — and
       was used in sixty places. There are two now, and both pass. */
    fgSubtle: p.fgMuted,
    fgOnEmphasis: p.buttonText,

    borderDefault: p.border,
    borderMuted: p.borderMuted,
    borderSubtle: p.borderMuted,

    accent: {
      /* Text and links: the darker step in light mode, brand green in dark. */
      fg: p.accentText,
      /* Fills: brand green in both, which is what keeps the identity constant. */
      emphasis: brand.accent,
      muted: withAlpha(brand.accent, 0.4),
      subtle: withAlpha(brand.accent, p.tintAlpha),
    },
    success: {
      fg: p.success,
      emphasis: brand.accent,
      muted: withAlpha(p.success, 0.4),
      subtle: withAlpha(p.success, p.tintAlpha),
    },
    danger: {
      fg: p.error,
      emphasis: p.error,
      muted: withAlpha(p.error, 0.4),
      subtle: withAlpha(p.error, p.tintAlpha),
    },
    attention: {
      fg: p.warning,
      emphasis: p.warning,
      muted: withAlpha(p.warning, 0.4),
      subtle: withAlpha(p.warning, p.tintAlpha),
    },
    neutral: {
      emphasisPlus: p.fg,
      emphasis: p.fgMuted,
      muted: withAlpha(p.fgMuted, 0.4),
      subtle: p.inset,
    },

    btn: {
      ...base.colors.btn,
      text: p.fg,
      bg: p.surface,
      border: p.border,
      hoverBg: p.inset,
      hoverBorder: p.controlBorder,
      activeBg: p.inset,
      selectedBg: p.inset,
      primary: {
        ...base.colors.btn.primary,
        /* Light darkens the fill so a white label can sit on it; dark keeps the
           brand green and darkens the label instead. White on #22C55E is
           2.28:1 either way, so one of the two has to give. */
        text: p.buttonText,
        bg: p.buttonBg,
        border: "rgba(0,0,0,0.12)",
        hoverBg: p.buttonHoverBg,
        hoverText: p.buttonText,
        hoverBorder: "rgba(0,0,0,0.12)",
        selectedBg: p.buttonActiveBg,
        disabledText: withAlpha(p.buttonText, 0.6),
        disabledBg: withAlpha(p.buttonBg, 0.5),
      },
      danger: {
        ...base.colors.btn.danger,
        text: p.error,
        hoverBg: p.error,
        hoverText: "#FFFFFF",
      },
    },

    underlineNav: {
      icon: p.fgMuted,
      borderActive: brand.accent,
    },
  },
});

const gitforgeLight = schemeFrom(palette.light, primerTheme.colorSchemes.light);
const gitforgeDark = schemeFrom(palette.dark, primerTheme.colorSchemes.dark);

export const gitforgeTheme = {
  ...primerTheme,
  fonts: {
    ...primerTheme.fonts,
    normal: fonts.body,
    mono: fonts.mono,
  },
  radii: [radii.sm, radii.md, radii.lg],
  colorSchemes: {
    ...primerTheme.colorSchemes,
    light: gitforgeLight,
    dark: gitforgeDark,
    dark_dimmed: gitforgeDark,
  },
};

/** Lane colours for the commit graph, resolved for the active scheme. */
export const lanesFor = (colorScheme) =>
  colorScheme === "light" ? laneColors.light : laneColors.dark;
