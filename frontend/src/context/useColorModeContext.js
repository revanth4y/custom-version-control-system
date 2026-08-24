import { useContext } from "react";

import { ColorModeContext } from "./ColorModeContext.jsx";

/** The colour mode, from the provider at the root. */
export const useColorModeContext = () => {
  const value = useContext(ColorModeContext);
  if (!value) {
    throw new Error("useColorModeContext must be used inside ColorModeProvider");
  }
  return value;
};
