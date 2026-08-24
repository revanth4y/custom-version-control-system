import { Link as RouterLink, useLocation } from "react-router-dom";
import { Box } from "@primer/react";

import { activeNavKey, navItemsFor } from "../../utils/navigation";

/**
 * The product-level navigation, for viewports wide enough to show it.
 *
 * The active entry is marked with a rule sitting on the header's own bottom
 * border, as in the reference. The rule is drawn by every item, transparent
 * when inactive, so switching pages cannot change an item's height and nudge
 * the row.
 */
const GlobalNav = ({ currentUser }) => {
  const { pathname } = useLocation();
  const items = navItemsFor(currentUser);
  const active = activeNavKey(items, pathname);

  if (items.length === 0) return null;

  return (
    <Box
      as="nav"
      aria-label="Global"
      sx={{ display: ["none", "none", "flex"], alignItems: "stretch", gap: 3, alignSelf: "stretch" }}
    >
      {items.map(({ key, label, to }) => (
        <Box
          key={key}
          as={RouterLink}
          to={to}
          aria-current={active === key ? "page" : undefined}
          sx={{
            display: "flex",
            alignItems: "center",
            px: 1,
            fontSize: 1,
            fontWeight: active === key ? 600 : 400,
            color: active === key ? "fg.default" : "fg.muted",
            textDecoration: "none",
            /* Sits on the header's border rather than above it, so the two read
               as one line rather than as two rules a pixel apart. */
            borderBottom: "2px solid",
            borderColor: active === key ? "accent.emphasis" : "transparent",
            mb: "-1px",
            "&:hover": { color: "fg.default" },
          }}
        >
          {label}
        </Box>
      ))}
    </Box>
  );
};

export default GlobalNav;
