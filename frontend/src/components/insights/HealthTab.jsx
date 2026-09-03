import { Box, Button, Label, Spinner, Text } from "@primer/react";
import {
  CheckCircleIcon,
  DatabaseIcon,
  FileIcon,
  FileDirectoryIcon,
  GitCommitIcon,
  ShieldCheckIcon,
  TagIcon,
  TrashIcon,
} from "@primer/octicons-react";

import Notice from "../common/Notice";
import StatCard, { StatGrid } from "./StatCard";
import { AsyncBoundary } from "../common/states";
import { count } from "./format";
import { formatBytes } from "../../utils/bytes";

/**
 * What the store holds, and whether it is sound.
 *
 * Only the two cheap figures load on arrival. Everything else here costs a full
 * pass over the object store — reading every object to size and type it, and
 * walking reachability while re-hashing to verify integrity — and that pass
 * takes the repository's exclusive lock. So it never runs on page load, on tab
 * change, or on a re-render: it runs when somebody presses the button, and not
 * before. Until then the unknown figures say so rather than showing a zero,
 * because "not measured" and "none found" are different answers.
 */
const ICONS = {
  blob: FileIcon,
  tree: FileDirectoryIcon,
  commit: GitCommitIcon,
  tag: TagIcon,
};

const INTEGRITY = {
  HEALTHY: { variant: "success", text: "Every object verified" },
  DAMAGED: { variant: "danger", text: "Damaged objects found" },
  TRUNCATED: { variant: "attention", text: "Verification stopped early" },
  NOT_VERIFIED: { variant: "secondary", text: "Not verified" },
};

const TYPES = ["blob", "tree", "commit", "tag"];

const HealthTab = ({
  health,
  storage,
  loading,
  error,
  reload,
  scanned,
  scanning,
  scanError,
  runScan,
}) => {
  const integrity = INTEGRITY[health?.integrity] ?? INTEGRITY.NOT_VERIFIED;
  const byType = new Map((storage?.byType ?? []).map((usage) => [usage.type, usage]));

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 3 }}>
      <Box
        sx={{
          border: "1px solid",
          borderColor: "border.default",
          borderRadius: 2,
          p: 3,
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          gap: 3,
          flexWrap: "wrap",
          bg: "canvas.subtle",
        }}
      >
        <Box sx={{ flex: "1 1 320px", minWidth: 0 }}>
          <Text sx={{ fontSize: 1, fontWeight: 600, display: "block" }}>Health scan</Text>
          <Text sx={{ fontSize: 0, color: "fg.muted" }}>
            Reads every object to size it, walks reachability, and re-hashes each object
            to verify it. This holds the repository lock while it runs, so it only
            happens when you ask for it.
          </Text>
        </Box>
        <Button variant="primary" onClick={runScan} disabled={scanning || loading}>
          {scanning ? <Spinner size="small" /> : null}
          <Box as="span" sx={{ ml: scanning ? 2 : 0 }}>
            {scanning ? "Scanning" : scanned ? "Run health scan again" : "Run health scan"}
          </Box>
        </Button>
      </Box>

      {scanError && <Notice variant="danger">{scanError}</Notice>}

      {!scanned && !scanning && (
        <Notice variant="info">
          Nothing has been scanned yet. Object sizes, the type breakdown, dangling
          objects and integrity are all unmeasured until you run the scan.
        </Notice>
      )}

      <AsyncBoundary
        loading={loading}
        error={error}
        onRetry={reload}
        loadingLabel="Loading health"
        minHeight="200px"
      >
        {health && (
          <>
            <StatGrid>
              <StatCard
                icon={DatabaseIcon}
                label="Objects stored"
                value={count(health.storedObjects)}
                hint="counted without reading them"
              />
              <StatCard
                icon={ShieldCheckIcon}
                label="Roots"
                value={count(health.roots)}
                hint="branches, HEAD, remote refs, tags, worktree"
              />
              <StatCard
                icon={CheckCircleIcon}
                label="Reachable objects"
                value={scanned ? count(health.reachableObjects) : "Not scanned"}
                hint={scanned ? "held by at least one root" : "run the scan to measure"}
              />
              <StatCard
                icon={TrashIcon}
                label="Dangling objects"
                value={scanned ? count(health.unreachableObjects) : "Not scanned"}
                hint={
                  scanned
                    ? `${formatBytes(health.unreachableBytes ?? 0)} no root reaches`
                    : "run the scan to measure"
                }
              />
              <StatCard
                icon={DatabaseIcon}
                label="Repository size"
                value={storage ? formatBytes(storage.scannedBytes) : "Not scanned"}
                hint={
                  storage
                    ? `${count(storage.scannedObjects)} objects read${
                        storage.truncated ? ", scan truncated" : ""
                      }`
                    : "run the scan to measure"
                }
              />
              {TYPES.map((type) => (
                <StatCard
                  key={type}
                  icon={ICONS[type]}
                  label={`${type} objects`}
                  value={storage ? count(byType.get(type)?.count ?? 0) : "Not scanned"}
                  hint={storage ? formatBytes(byType.get(type)?.bytes ?? 0) : "run the scan to measure"}
                />
              ))}
            </StatGrid>

            <Box
              sx={{
                border: "1px solid",
                borderColor: "border.default",
                borderRadius: 2,
                p: 3,
                display: "flex",
                flexDirection: "column",
                gap: 2,
              }}
            >
              <Box sx={{ display: "flex", alignItems: "center", gap: 2, flexWrap: "wrap" }}>
                <Text sx={{ fontSize: 1, fontWeight: 600 }}>Integrity</Text>
                <Label variant={integrity.variant}>{health.integrity}</Label>
                <Text sx={{ fontSize: 0, color: "fg.muted" }}>{integrity.text}</Text>
              </Box>

              <Text sx={{ fontSize: 0, color: "fg.muted" }}>
                {scanned
                  ? `${count(health.verifiedObjects)} verified, ${count(
                      health.damagedObjects,
                    )} damaged${health.integrityTruncated ? ", verification truncated" : ""}`
                  : "Integrity is reported only after a scan. No claim is made about these objects until then."}
              </Text>

              {scanned && (
                <Text sx={{ fontSize: 0, color: "fg.muted" }}>
                  {health.fullyReachable
                    ? "Every stored object is reachable; collection would free nothing."
                    : `${count(health.unreachableObjects)} objects would be candidates for collection.`}
                  {health.retained === null || health.retained === undefined
                    ? ""
                    : ` ${count(health.retained)} retained by the grace period.`}
                  {health.scanTruncated ? " The reachability walk was truncated." : ""}
                  {health.scanDurationMs === null || health.scanDurationMs === undefined
                    ? ""
                    : ` Scan took ${count(health.scanDurationMs)} ms.`}
                </Text>
              )}

              <Text sx={{ fontSize: 0, color: "fg.subtle" }}>
                This is a report, not an action. Nothing is deleted by scanning.
              </Text>
            </Box>
          </>
        )}
      </AsyncBoundary>
    </Box>
  );
};

export default HealthTab;
