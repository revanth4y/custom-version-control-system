import { ThemeProvider } from "@primer/react";

import { useColorMode } from "../hooks/useColorMode";
import { gitforgeTheme } from "../theme/gitforge";
import { ColorModeContext } from "./ColorModeContext.jsx";

/**
 * Applies the reader's colour preference to Primer and to the page.
 *
 * Primer is told the resolved scheme rather than `auto`. Resolving it ourselves
 * is what lets the same answer drive the `data-theme` attribute that paints the
 * page before React mounts, and the commit graph's lane colours, which are SVG
 * strokes and cannot read a CSS variable.
 */
export const ColorModeProvider = ({ children }) => {
  const colorMode = useColorMode();

  return (
    <ColorModeContext.Provider value={colorMode}>
      <ThemeProvider
        theme={gitforgeTheme}
        colorMode={colorMode.scheme === "dark" ? "night" : "day"}
        dayScheme="light"
        nightScheme="dark"
      >
        {children}
      </ThemeProvider>
    </ColorModeContext.Provider>
  );
};
