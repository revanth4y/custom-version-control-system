import { useCallback, useMemo } from "react";

import { RepositoryContext } from "./RepositoryContext";
import { useAsync } from "../hooks/useAsync";
import { useAuth } from "../hooks/useAuth";
import { repoService } from "../services/repoService";
import { branchService } from "../services/branchService";

/**
 * Loads a repository once for the whole shell.
 *
 * Metadata and HEAD are fetched here rather than in each tab, so moving between
 * tabs re-renders without re-requesting. The dependency is the owner/name pair,
 * so navigating to a *different* repository does refetch — which is the only
 * time it should.
 */
export const RepositoryProvider = ({ owner, name, children }) => {
  const { currentUser } = useAuth();

  const repository = useAsync(() => repoService.get(owner, name), [owner, name]);
  const head = useAsync(() => branchService.head(owner, name), [owner, name]);

  // Reloading HEAD alone is what a branch switch needs; the metadata is unchanged.
  const reloadHead = useCallback(() => head.reload(), [head]);

  const value = useMemo(
    () => ({
      owner,
      name,
      repository: repository.data,
      head: head.data,
      loading: repository.loading || head.loading,
      error: repository.error ?? head.error,
      reload: () => {
        repository.reload();
        head.reload();
      },
      reloadHead,
      // Write controls are hidden for anyone who is not the owner. The server
      // enforces this regardless; hiding them only avoids offering an action
      // that would be refused.
      canWrite: Boolean(currentUser && repository.data?.ownerUsername === currentUser.username),
    }),
    [owner, name, repository, head, reloadHead, currentUser],
  );

  return <RepositoryContext.Provider value={value}>{children}</RepositoryContext.Provider>;
};
