import { Box } from "@primer/react";

/**
 * Horizontal bounds for page content.
 *
 * One place decides how wide a page may grow and how much air it keeps at the
 * edges, so every screen lines up with every other.
 */
const PageContainer = ({ children, width = "large", sx = {} }) => {
  const maxWidth = { medium: "768px", large: "1280px", full: "100%" }[width];

  return (
    <Box sx={{ width: "100%", maxWidth, mx: "auto", px: [3, 3, 4], py: [3, 4], ...sx }}>
      {children}
    </Box>
  );
};

export default PageContainer;
