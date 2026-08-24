/**
 * The horizontal bounds every full-width surface shares.
 *
 * The header used to set its own padding and run edge to edge while pages were
 * capped and centred. Below about 1330px nobody could tell, because the cap was
 * never reached; above it the brand sat 80px to the left of the content it was
 * supposed to head. Both now read from here, so the two cannot drift apart
 * again — the reference shows the brand mark sharing a left edge with the
 * repository name beneath it.
 */
export const PAGE_MAX_WIDTHS = {
  medium: "768px",
  large: "1280px",
  full: "100%",
};

/** Gutters at [narrow, wide] viewports, in Primer spacing steps. */
export const PAGE_GUTTER = [3, 3, 4];
