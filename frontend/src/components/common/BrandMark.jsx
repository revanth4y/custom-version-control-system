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
 *
 * The colour is set here rather than inherited. The brand green is one value in
 * both themes, while the surrounding text accent is not — light mode darkens it
 * to #15803D so that links can be read, and a mark that inherited that would
 * quietly stop being the brand colour on half the site. Named as the accent
 * emphasis token rather than written as a hex, so it still moves with the theme
 * if the brand ever changes.
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
      color: "var(--bgColor-accent-emphasis)",
    }}
    {...rest}
  >
    <GitForgeMark size={size} title={title} />
  </span>
);

export default BrandMark;
