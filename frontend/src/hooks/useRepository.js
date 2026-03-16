import { useContext } from "react";

import { RepositoryContext } from "../context/RepositoryContext";

/** The repository the surrounding shell loaded. */
export function useRepository() {
  const value = useContext(RepositoryContext);
  if (!value) {
    throw new Error("useRepository must be used inside a RepositoryProvider");
  }
  return value;
}
