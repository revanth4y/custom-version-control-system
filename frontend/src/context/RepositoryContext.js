import { createContext } from "react";

/**
 * Repository metadata and HEAD, shared by every tab of a repository.
 *
 * Held in context so switching between Code, Commits and the rest does not
 * refetch what the shell already loaded once.
 */
export const RepositoryContext = createContext(null);
