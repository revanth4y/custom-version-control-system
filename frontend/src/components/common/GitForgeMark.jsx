/**
 * The GitForge Engine mark: a branch leaving a trunk, inside a terminal.
 *
 * Inlined rather than loaded through an `<img>` so it can inherit the
 * surrounding colour. An external SVG gets no CSS context, so `currentColor`
 * resolves to black — which on the dark canvas renders the mark invisible.
 * One colour throughout is also what lets a single file serve both themes.
 *
 * Drawn on a 24 grid with the interior motif centred at x=12, so it sits in
 * the middle of the screen rather than drifting left. Six elements and nothing
 * more: at 16px in a header, anything finer turns to mush.
 *
 * Deliberately not a fork with two outgoing edges. That reads as the system
 * "share" glyph — recognisable enough that people would see an action rather
 * than a product — which only became obvious when an earlier draft was
 * rendered large.
 *
 * Original artwork, and unlike any existing service's mark.
 */
const GitForgeMark = ({ size = 24, title, ...rest }) => (
  <svg
    xmlns="http://www.w3.org/2000/svg"
    viewBox="0 0 24 24"
    width={size}
    height={size}
    fill="none"
    role={title ? "img" : "presentation"}
    aria-label={title}
    aria-hidden={title ? undefined : true}
    style={{ display: "block", flexShrink: 0 }}
    {...rest}
  >
    {/* The terminal: screen and stand. */}
    <rect
      x="2.4"
      y="3.4"
      width="19.2"
      height="14.2"
      rx="2.6"
      stroke="currentColor"
      strokeWidth="1.7"
    />
    <path
      d="M12 17.6v2.6M8.6 20.8h6.8"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
    />

    {/* A trunk, and one branch leaving it. */}
    <path d="M9.4 8.3v4.4" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
    <path
      d="M9.4 10.5h3a2.2 2.2 0 0 0 2.2-2.2V8.1"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
    />
    <circle cx="9.4" cy="7.3" r="1.5" fill="currentColor" />
    <circle cx="9.4" cy="13.7" r="1.5" fill="currentColor" />
    <circle cx="14.6" cy="7.3" r="1.5" fill="currentColor" />
  </svg>
);

export default GitForgeMark;
