import { createContext } from "react";

/**
 * Holds the signed-in user and the auth actions.
 *
 * Kept in its own module (no component exports) so React Fast Refresh can
 * reliably hot-reload the provider and the hook.
 */
export const AuthContext = createContext(null);
