import { createContext } from "react";

/**
 * The active colour preference, shared with anything that needs to know.
 *
 * Held in context rather than called per component so there is exactly one
 * subscription to the media query and one writer of `data-theme`. Several
 * copies of the hook would each attach their own listener and race each other
 * to set the attribute.
 */
export const ColorModeContext = createContext(null);
