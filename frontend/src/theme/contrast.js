/**
 * WCAG 2.1 relative luminance and contrast.
 *
 * Alpha is composited before luminance is taken. V1 shipped a measurement that
 * treated translucent backgrounds as opaque and reported an impossible ratio of
 * 1.0 for a pair that was actually fine — the tints used by diff rows and
 * subtle badges are exactly where that goes wrong, so they are the pairs most
 * worth measuring correctly.
 */

const channel = (value) => {
  const c = value / 255;
  return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
};

/** Accepts `#rrggbb` or `rgba(r, g, b, a)`. */
export const parseColor = (color) => {
  const rgba = color.match(/rgba?\(([^)]+)\)/i);
  if (rgba) {
    const parts = rgba[1].split(",").map((part) => Number(part.trim()));
    return { r: parts[0], g: parts[1], b: parts[2], a: parts.length > 3 ? parts[3] : 1 };
  }

  const hex = color.replace("#", "");
  return {
    r: parseInt(hex.slice(0, 2), 16),
    g: parseInt(hex.slice(2, 4), 16),
    b: parseInt(hex.slice(4, 6), 16),
    a: 1,
  };
};

/** Flattens a translucent colour onto the surface behind it. */
export const composite = (color, backdrop) => {
  const top = parseColor(color);
  const under = parseColor(backdrop);
  if (top.a >= 1) return { r: top.r, g: top.g, b: top.b, a: 1 };

  return {
    r: Math.round(top.a * top.r + (1 - top.a) * under.r),
    g: Math.round(top.a * top.g + (1 - top.a) * under.g),
    b: Math.round(top.a * top.b + (1 - top.a) * under.b),
    a: 1,
  };
};

export const luminance = (color, backdrop = "#FFFFFF") => {
  const { r, g, b } = composite(color, backdrop);
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
};

/**
 * Contrast between a foreground and a background.
 *
 * @param backdrop what sits behind the background, when the background is
 *     itself translucent
 */
export const contrast = (foreground, background, backdrop = "#FFFFFF") => {
  const flatBackground = composite(background, backdrop);
  const behind = `rgb(${flatBackground.r}, ${flatBackground.g}, ${flatBackground.b})`;

  const a = luminance(foreground, behind);
  const b = luminance(behind, behind);
  const [lighter, darker] = a > b ? [a, b] : [b, a];

  return (lighter + 0.05) / (darker + 0.05);
};

/** 4.5:1 for body text, 3:1 for large text and for identifying a control. */
export const AA_TEXT = 4.5;
export const AA_LARGE = 3;
