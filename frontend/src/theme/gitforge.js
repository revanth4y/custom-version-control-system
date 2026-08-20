import { theme as primerTheme } from "@primer/react";

/**
 * GitForge design tokens.
 *
 * A slate canvas with an ember accent — deliberately distinct from the
 * blue-on-near-black that most code hosts use. "Forge" suggests heated metal, so
 * the accent is copper rather than blue, and the surfaces are cool to let it
 * carry the emphasis.
 *
 * These are the source of truth. Everything below maps them onto Primer's
 * variable names so its components inherit the identity instead of being
 * overridden component by component.
 */
export const tokens = {
  canvas: "#14161C",
  raised: "#1B1E26",
  overlay: "#22262F",
  border: "#2D323D",
  borderMuted: "#242832",
  /* Brighter than layout borders so an input reads as editable. */
  controlBorder: "#3A4150",
  fg: "#E4E7EC",
  muted: "#9AA3B2",
  subtle: "#6E7784",
  ember: "#E0763D",
  emberBright: "#F08E5A",
  emberMuted: "#8A4A26",
  emberSubtle: "rgba(224, 118, 61, 0.14)",
  success: "#4F9D69",
  successSubtle: "rgba(79, 157, 105, 0.14)",
  danger: "#D9534F",
  dangerSubtle: "rgba(217, 83, 79, 0.14)",
  attention: "#D8A33C",
  attentionSubtle: "rgba(216, 163, 60, 0.14)",
};

/** Lane colours for the commit graph, reused by any categorical colouring. */
export const laneColors = [
  tokens.ember,
  tokens.success,
  "#5B8DD9",
  "#B06FC4",
  tokens.attention,
  "#4AA8A0",
];

export const fonts = {
  body: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif',
  mono: 'ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, "Liberation Mono", monospace',
};

/**
 * Primer's dark scheme with GitForge colours substituted.
 *
 * Only the variables Primer actually reads are replaced, so components keep
 * their built-in behaviour — focus rings, disabled states, elevation — while
 * taking on this palette.
 */
const gitforgeDark = {
  ...primerTheme.colorSchemes.dark,
  colors: {
    ...primerTheme.colorSchemes.dark.colors,

    canvasDefault: tokens.canvas,
    canvasOverlay: tokens.overlay,
    canvasInset: "#101218",
    canvasSubtle: tokens.raised,

    fgDefault: tokens.fg,
    fgMuted: tokens.muted,
    fgSubtle: tokens.subtle,
    fgOnEmphasis: "#FFFFFF",

    borderDefault: tokens.border,
    borderMuted: tokens.borderMuted,
    borderSubtle: tokens.borderMuted,

    accent: {
      fg: tokens.emberBright,
      emphasis: tokens.ember,
      muted: tokens.emberMuted,
      subtle: tokens.emberSubtle,
    },
    success: {
      fg: tokens.success,
      emphasis: tokens.success,
      muted: "rgba(79, 157, 105, 0.4)",
      subtle: tokens.successSubtle,
    },
    danger: {
      fg: tokens.danger,
      emphasis: tokens.danger,
      muted: "rgba(217, 83, 79, 0.4)",
      subtle: tokens.dangerSubtle,
    },
    attention: {
      fg: tokens.attention,
      emphasis: tokens.attention,
      muted: "rgba(216, 163, 60, 0.4)",
      subtle: tokens.attentionSubtle,
    },
    neutral: {
      emphasisPlus: tokens.fg,
      emphasis: tokens.subtle,
      muted: "rgba(110, 119, 132, 0.4)",
      subtle: tokens.overlay,
    },

    // Primer reads these for buttons; without them the default dark greys leak
    // through and sit oddly against the warmer canvas.
    btn: {
      ...primerTheme.colorSchemes.dark.colors.btn,
      text: tokens.fg,
      bg: tokens.overlay,
      border: tokens.border,
      hoverBg: "#2A2F3A",
      hoverBorder: "#3A414F",
      activeBg: "#32384499",
      selectedBg: "#2A2F3A",
      primary: {
        ...primerTheme.colorSchemes.dark.colors.btn.primary,
        text: "#FFFFFF",
        bg: tokens.ember,
        border: "rgba(0,0,0,0.2)",
        hoverBg: tokens.emberBright,
        hoverBorder: "rgba(0,0,0,0.2)",
        selectedBg: tokens.emberMuted,
        disabledText: "rgba(255,255,255,0.5)",
        disabledBg: "rgba(224, 118, 61, 0.5)",
      },
      danger: {
        ...primerTheme.colorSchemes.dark.colors.btn.danger,
        text: tokens.danger,
        hoverBg: tokens.danger,
        hoverText: "#FFFFFF",
      },
    },

    // The underline that marks the current tab.
    underlineNav: {
      icon: tokens.muted,
      borderActive: tokens.ember,
    },
  },
};

export const gitforgeTheme = {
  ...primerTheme,
  fonts: {
    ...primerTheme.fonts,
    normal: fonts.body,
    mono: fonts.mono,
  },
  colorSchemes: {
    ...primerTheme.colorSchemes,
    dark: gitforgeDark,
    // Only one scheme is supported for now; mapping light to the same values
    // keeps a system preference for light from rendering an unstyled page.
    light: gitforgeDark,
    dark_dimmed: gitforgeDark,
  },
};
