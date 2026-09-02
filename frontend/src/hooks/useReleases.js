import { useAsync } from "./useAsync";
import { releaseService } from "../services/releaseService";

/** The repository's releases, newest first. Drafts appear only for the owner. */
export function useReleases(owner, name) {
  const query = useAsync(() => releaseService.list(owner, name), [owner, name]);

  return {
    releases: query.data ?? [],
    // Distinguishes "not fetched yet" from "fetched and empty", which need
    // different things on screen.
    loaded: Array.isArray(query.data),
    loading: query.loading,
    error: query.error,
    reload: query.reload,
  };
}

/** Every tag, with what each one ultimately names. */
export function useTags(owner, name) {
  const query = useAsync(() => releaseService.listTags(owner, name), [owner, name]);

  return {
    tags: query.data ?? [],
    loaded: Array.isArray(query.data),
    loading: query.loading,
    error: query.error,
    reload: query.reload,
  };
}
