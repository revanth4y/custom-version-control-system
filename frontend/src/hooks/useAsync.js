import { useCallback, useEffect, useRef, useState } from "react";

import { errorMessage } from "../services/api";

/**
 * Runs an async function and tracks its loading, error and data states.
 *
 * The whole of the app's server-state handling. A query library would add
 * caching and deduplication, but this application is read-light and the
 * behaviour it would buy is not yet worth another dependency.
 *
 * @param task an async function; must be stable or wrapped in useCallback
 * @param deps re-runs the task when these change
 */
export function useAsync(task, deps = [], { immediate = true } = {}) {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(immediate);

  // Guards against setting state after unmount, and against a slow earlier
  // request overwriting the result of a newer one.
  const requestId = useRef(0);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const run = useCallback(async () => {
    const id = ++requestId.current;
    setLoading(true);
    setError(null);

    try {
      const result = await task();
      if (mounted.current && id === requestId.current) {
        setData(result);
      }
      return result;
    } catch (caught) {
      if (mounted.current && id === requestId.current) {
        setError(errorMessage(caught));
      }
      return undefined;
    } finally {
      if (mounted.current && id === requestId.current) {
        setLoading(false);
      }
    }
    // The dependency list comes from the caller, so the linter cannot verify it
    // statically. `task` is deliberately excluded: callers pass an inline
    // function, and including it would rebuild the callback every render and
    // loop. The caller's `deps` describe when the task should genuinely re-run.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  useEffect(() => {
    if (immediate) {
      run();
    }
  }, [run, immediate]);

  return { data, error, loading, reload: run, setData };
}
