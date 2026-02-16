import { Box } from "@primer/react";
import { Outlet } from "react-router-dom";

import AppHeader from "./AppHeader";

/**
 * Page frame: header, then the routed page.
 *
 * A single min-height column so short pages still fill the viewport and the
 * canvas colour reaches the bottom of the window.
 */
const AppShell = () => (
  <Box sx={{ minHeight: "100vh", display: "flex", flexDirection: "column", bg: "canvas.default" }}>
    <AppHeader />
    <Box as="main" sx={{ flex: 1, display: "flex", flexDirection: "column" }}>
      <Outlet />
    </Box>
  </Box>
);

export default AppShell;
