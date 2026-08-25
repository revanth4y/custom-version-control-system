import GitForgeMark from "./GitForgeMark";
import { brand, radii, terminal } from "../../theme/palette";

/**
 * The brand symbol, wherever the brand appears.
 *
 * Every surface renders this rather than a specific mark, so the final symbol
 * can be swapped by changing one import here — no header, favicon or auth
 * screen needs touching, and none of them can drift to a different glyph.
 *
 * The box is fixed regardless of the artwork inside it. A replacement symbol
 * with different proportions therefore cannot change the height of the header
 * or shift what sits beside it: the mark scales to the frame, the frame does
 * not scale to the mark.
 *
 * `tiled` sets the mark on a filled green square, as the reference header has
 * it. Both of its colours are the ones the palette already fixes across themes
 * — the brand green, and the terminal black the mark depicts — so the tile
 * reads identically on either canvas and needs no per-theme handling. Dark on
 * that green measures about 8:1, well clear of the 3:1 a graphic needs.
 *
 * Untiled, the colour is set here rather than inherited. The brand green is one
 * value in both themes, while the surrounding text accent is not — light mode
 * darkens it to #15803D so that links can be read, and a mark that inherited
 * that would quietly stop being the brand colour on half the site. Named as the
 * accent emphasis token rather than written as a hex, so it still moves with the
 * theme if the brand ever changes.
 */
const BrandMark = ({ size = 22, title, tiled = false, ...rest }) => {
  /* The glyph sits at roughly two thirds of the tile so the fill reads as a
     surface the mark rests on rather than a border drawn around it. */
  const glyphSize = tiled ? Math.round(size * 0.66) : size;

  return (
    <span
      style={{
        display: "inline-flex",
        alignItems: "center",
        justifyContent: "center",
        width: size,
        height: size,
        flexShrink: 0,
        lineHeight: 0,
        ...(tiled
          ? { background: brand.accent, color: terminal, borderRadius: radii.md }
          : { color: "var(--bgColor-accent-emphasis)" }),
      }}
      {...rest}
    >
      <GitForgeMark size={glyphSize} title={title} />
    </span>
  );
};

export default BrandMark;
