import { useCallback, useEffect, useState } from "react";

import { useAsync } from "./useAsync";
import { errorMessage } from "../services/api";
import { insightsService } from "../services/insightsService";

/**
 * The Insights surfaces, one hook per API endpoint.
 *
 * Each tab fetches only what it shows. Loading all eleven endpoints up front
 * would make the cheap tabs wait on the expensive ones for data most visits
 * never look at.
 */

/** The window and grain the range-driven tabs are asked for. */
export function useInsightsRange() {
  const [applied, setApplied] = useState({ from: "", to: "", bucket: "day" });
  const [draft, setDraft] = useState({ from: "", to: "", bucket: "day" });

  const apply = useCallback(() => setApplied(draft), [draft]);
  const reset = useCallback(() => {
    const empty = { from: "", to: "", bucket: "day" };
    setDraft(empty);
    setApplied(empty);
  }, []);

  // The bucket takes effect immediately: it is a view of the same window, not a
  // different question, so making it wait behind Apply would only confuse.
  const setBucket = useCallback((bucket) => {
    setDraft((current) => ({ ...current, bucket }));
    setApplied((current) => ({ ...current, bucket }));
  }, []);

  return {
    applied,
    draft,
    setFrom: (from) => setDraft((current) => ({ ...current, from })),
    setTo: (to) => setDraft((current) => ({ ...current, to })),
    setBucket,
    apply,
    reset,
  };
}

export function useCommitSeries(owner, name, range) {
  const { from, to, bucket } = range;
  const query = useAsync(
    () => insightsService.commitSeries(owner, name, { from, to, bucket }),
    [owner, name, from, to, bucket],
  );
  return query;
}

export function useActivityInsights(owner, name, range) {
  const { from, to, bucket } = range;
  return useAsync(
    () => insightsService.activity(owner, name, { from, to, bucket }),
    [owner, name, from, to, bucket],
  );
}

export function useCommitInsights(owner, name) {
  return useAsync(() => insightsService.commits(owner, name), [owner, name]);
}

export function useContributorInsights(owner, name, range) {
  const { from, to } = range;
  return useAsync(
    () => insightsService.contributors(owner, name, { from, to }),
    [owner, name, from, to],
  );
}

export function useBranchInsights(owner, name) {
  return useAsync(() => insightsService.branches(owner, name), [owner, name]);
}

export function useRefInsights(owner, name) {
  return useAsync(() => insightsService.refs(owner, name), [owner, name]);
}

export function useTagInsights(owner, name) {
  return useAsync(() => insightsService.tags(owner, name), [owner, name]);
}

/**
 * Repository health, in two halves.
 *
 * What arrives on its own is only what can be answered without reading the
 * store: how many objects it holds, and how many roots protect them. Both of
 * the expensive passes — reading every object to size it and type it, and
 * walking reachability while re-hashing to verify integrity — wait for the
 * button. Mounting this hook must not cost the repository anything, because a
 * scan holds the repository's exclusive lock while it runs.
 */
export function useRepositoryHealth(owner, name) {
  const cheap = useAsync(() => insightsService.health(owner, name), [owner, name]);

  const [scan, setScan] = useState(null);
  const [storage, setStorage] = useState(null);
  const [scanning, setScanning] = useState(false);
  const [scanError, setScanError] = useState(null);

  // A new repository invalidates whatever the previous one's scan found.
  useEffect(() => {
    setScan(null);
    setStorage(null);
    setScanError(null);
  }, [owner, name]);

  const runScan = useCallback(async () => {
    setScanning(true);
    setScanError(null);
    try {
      const [scanned, usage] = await Promise.all([
        insightsService.health(owner, name, { scan: true }),
        insightsService.storage(owner, name),
      ]);
      setScan(scanned);
      setStorage(usage);
    } catch (caught) {
      setScanError(errorMessage(caught));
    } finally {
      setScanning(false);
    }
  }, [owner, name]);

  return {
    // The scan supersedes the cheap answer once it exists; until then the cheap
    // one stands, and the view marks it as unverified.
    health: scan ?? cheap.data,
    storage,
    loading: cheap.loading,
    error: cheap.error,
    reload: cheap.reload,
    scanned: Boolean(scan),
    scanning,
    scanError,
    runScan,
  };
}
