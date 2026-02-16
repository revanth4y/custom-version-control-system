import { useAsync } from "./useAsync";
import { branchService } from "../services/branchService";

/**
 * The repository's branches, with their tip commits.
 *
 * `enabled` exists for the selector, which should not fetch a branch list until
 * someone opens it — most visits to a repository never do.
 */
export function useBranches(owner, name, { enabled = true } = {}) {
  const query = useAsync(
    () => (enabled ? branchService.list(owner, name) : Promise.resolve(null)),
    [enabled, owner, name],
  );

  return {
    branches: query.data ?? [],
    // Distinguishes "not fetched yet" from "fetched and empty", which need
    // different things on screen.
    loaded: Array.isArray(query.data),
    loading: query.loading,
    error: query.error,
    reload: query.reload,
  };
}
