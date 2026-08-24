import { Box } from "@primer/react";

import { PAGE_GUTTER, PAGE_MAX_WIDTHS } from "./pageBounds";

/**
 * Horizontal bounds for page content.
 *
 * One place decides how wide a page may grow and how much air it keeps at the
 * edges, so every screen lines up with every other — and, since the header
 * reads the same constants, with the header above it.
 */
const PageContainer = ({ children, width = "large", sx = {} }) => (
  <Box
    sx={{ width: "100%", maxWidth: PAGE_MAX_WIDTHS[width], mx: "auto", px: PAGE_GUTTER, py: [3, 4], ...sx }}
  >
    {children}
  </Box>
);

export default PageContainer;
