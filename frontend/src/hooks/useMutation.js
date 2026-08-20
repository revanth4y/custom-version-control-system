import { useCallback, useEffect, useRef, useState } from "react";

import { errorMessage } from "../services/api";

/**
 * Tracks one in-flight write and whatever it failed with.
 *
 * Creating a branch, deleting one and moving HEAD all need the same three
 * things: a pending flag to disable the control, the server's own message when
 * it refuses, and a way to clear that message when the user edits their input.
 * Writing it three times is how the three drift apart.
 *
 * The server's message is preferred over anything invented here — it is the
 * authority on why a write was refused, and says things the client cannot know,
 * such as which branch HEAD is currently on.
 */
export function useMutation() {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState(null);

  // A dialog can be closed while its request is still in flight; settling that
  // request must not then set state on a component that is gone.
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);

  /**
   * @param recover optional. Some refusals carry the answer rather than a
   *     failure - a merge conflict arrives as a 409 whose body is the complete
   *     result. When this returns something, it is handed back as `recovered`
   *     and no error message is set, because nothing went wrong.
   */
  const run = useCallback(async (perform, fallback, recover) => {
    setPending(true);
    setError(null);
    try {
      const value = await perform();
      return { ok: true, value };
    } catch (caught) {
      const recovered = recover?.(caught) ?? null;
      if (recovered !== null) {
        return { ok: false, recovered, error: caught };
      }
      if (alive.current) setError(errorMessage(caught, fallback));
      return { ok: false, recovered: null, error: caught };
    } finally {
      if (alive.current) setPending(false);
    }
  }, []);

  const clearError = useCallback(() => setError(null), []);

  return { run, pending, error, clearError };
}
