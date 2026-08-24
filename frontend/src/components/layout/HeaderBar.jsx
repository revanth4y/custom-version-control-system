import { Box } from "@primer/react";

import { PAGE_GUTTER, PAGE_MAX_WIDTHS } from "./pageBounds";

/**
 * The bar itself: full-bleed background, content held to the page's own bounds.
 *
 * The background and the bottom border run the whole width of the window, while
 * what sits on the bar is capped and centred exactly like a page. That is what
 * keeps the brand directly above the repository name on a wide display instead
 * of 80px to its left.
 *
 * Sticky, because the reference keeps the navigation reachable while reading a
 * long file, and z-index above the page so nothing scrolls over it.
 */
const HeaderBar = ({ children }) => (
  <Box
    as="header"
    sx={{
      bg: "canvas.subtle",
      borderBottom: "1px solid",
      borderColor: "border.default",
      position: "sticky",
      top: 0,
      zIndex: 10,
    }}
  >
    <Box
      sx={{
        width: "100%",
        maxWidth: PAGE_MAX_WIDTHS.large,
        mx: "auto",
        px: PAGE_GUTTER,
        height: "64px",
        display: "flex",
        alignItems: "stretch",
        gap: [2, 2, 3],
      }}
    >
      {children}
    </Box>
  </Box>
);

export default HeaderBar;
