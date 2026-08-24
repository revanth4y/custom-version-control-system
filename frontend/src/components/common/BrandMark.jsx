import GitForgeMark from "./GitForgeMark";

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
 */
const BrandMark = ({ size = 22, title, ...rest }) => (
  <span
    style={{
      display: "inline-flex",
      alignItems: "center",
      justifyContent: "center",
      width: size,
      height: size,
      flexShrink: 0,
      lineHeight: 0,
    }}
    {...rest}
  >
    <GitForgeMark size={size} title={title} />
  </span>
);

export default BrandMark;
