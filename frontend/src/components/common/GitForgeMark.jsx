/**
 * The GitForge mark: a commit graph forking and rejoining.
 *
 * Inlined rather than loaded through an `<img>` so it can inherit the
 * surrounding colour. An external SVG gets no CSS context, so `currentColor`
 * resolves to black — which on this canvas renders the mark invisible.
 *
 * Original artwork; deliberately unlike any existing service's mark.
 */
const GitForgeMark = ({ size = 24, title, ...rest }) => (
  <svg
    xmlns="http://www.w3.org/2000/svg"
    viewBox="0 0 32 32"
    width={size}
    height={size}
    fill="none"
    role={title ? "img" : "presentation"}
    aria-label={title}
    aria-hidden={title ? undefined : true}
    style={{ display: "block", flexShrink: 0 }}
    {...rest}
  >
    <circle cx="16" cy="5" r="3" fill="currentColor" />
    <circle cx="7" cy="16" r="3" fill="currentColor" />
    <circle cx="25" cy="16" r="3" fill="currentColor" />
    <circle cx="16" cy="27" r="3" fill="currentColor" />
    <path
      d="M16 8v4a4 4 0 0 1-4 4H10"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
    />
    <path
      d="M16 8v4a4 4 0 0 0 4 4h2"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
    />
    <path
      d="M16 24v-4a4 4 0 0 0-4-4H10"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
    />
    <path
      d="M16 24v-4a4 4 0 0 1 4-4h2"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
    />
  </svg>
);

export default GitForgeMark;
