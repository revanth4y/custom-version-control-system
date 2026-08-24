import { Link as RouterLink } from "react-router-dom";
import { Box, Text } from "@primer/react";

import BrandMark from "../common/BrandMark";

/**
 * The mark and the product name, as one link home.
 *
 * The reference sets the name over a second, quieter line rather than beside
 * it, which is what gives the header its height. Both lines are inside the
 * link, so the whole lockup is one target instead of a small icon next to some
 * text that happens to look clickable.
 *
 * The second line is dropped on narrow viewports. At 390 the header also has to
 * hold the menu, the theme control and the account, and two lines of brand
 * leaves those fighting for what is left.
 */
const BrandLockup = () => (
  <Box
    as={RouterLink}
    to="/"
    sx={{
      display: "flex",
      alignItems: "center",
      gap: 2,
      flexShrink: 0,
      color: "fg.default",
      textDecoration: "none",
      borderRadius: 2,
      "&:hover .brand-name": { color: "accent.fg" },
    }}
  >
    <BrandMark size={28} />

    <Box sx={{ display: "flex", flexDirection: "column", justifyContent: "center", lineHeight: 1.1 }}>
      <Text
        className="brand-name"
        sx={{ fontWeight: 600, fontSize: 2, letterSpacing: "-0.01em", lineHeight: 1.2 }}
      >
        GitForge
      </Text>
      <Text
        sx={{
          display: ["none", "none", "block"],
          fontSize: 0,
          color: "fg.muted",
          lineHeight: 1.2,
        }}
      >
        GitForge Engine
      </Text>
    </Box>
  </Box>
);

export default BrandLockup;
