import { Box, Text } from "@primer/react";
import {
  CalendarIcon,
  GitBranchIcon,
  GitCommitIcon,
  GitMergeIcon,
  GraphIcon,
  PeopleIcon,
  PulseIcon,
  TagIcon,
} from "@primer/octicons-react";

import StatCard, { StatGrid } from "./StatCard";
import { AsyncBoundary } from "../common/states";
import { count, daysBetween, humanDuration } from "./format";

/**
 * The eight figures that describe a repository at a glance.
 *
 * Each one is a number the backend counted, not a number this page derived from
 * a different number — except the two marked as averages, whose arithmetic is
 * spelled out in the card so nobody has to guess what it divides by.
 */
const OverviewTab = ({ overview, dag, refs }) => {
  const loading = overview.loading || dag.loading || refs.loading;
  const error = overview.error || dag.error || refs.error;

  const reload = () => {
    overview.reload();
    dag.reload();
    refs.reload();
  };

  const summary = overview.data;
  const shape = dag.data;
  const references = refs.data;

  // History runs from the first commit to now: a repository whose last commit
  // was a year ago is still that old today.
  const ageSeconds = shape?.earliestCommit
    ? Math.floor((Date.now() - Date.parse(shape.earliestCommit)) / 1000)
    : null;

  const spanDays =
    shape?.earliestCommit && shape?.latestCommit
      ? daysBetween(shape.earliestCommit, shape.latestCommit)
      : null;

  const activeDays = summary?.activity?.length ?? null;

  const average =
    shape && spanDays
      ? (shape.commits / spanDays).toFixed(spanDays === 1 ? 0 : 2)
      : null;

  return (
    <AsyncBoundary
      loading={loading}
      error={error}
      onRetry={reload}
      loadingLabel="Counting the repository"
      minHeight="240px"
    >
      {summary && shape && references && (
        <Box sx={{ display: "flex", flexDirection: "column", gap: 3 }}>
          <StatGrid>
            <StatCard
              icon={GitCommitIcon}
              label="Total commits"
              value={count(shape.commits)}
              hint="reachable from any branch, tag or HEAD"
            />
            <StatCard
              icon={PeopleIcon}
              label="Contributors"
              value={count(summary.contributors.length)}
              hint="distinct commit authors"
            />
            <StatCard
              icon={GitBranchIcon}
              label="Branches"
              value={count(references.branches)}
              hint="local branch references"
            />
            <StatCard
              icon={TagIcon}
              label="Tags"
              value={count(references.tags)}
              hint="annotated and lightweight"
            />
            <StatCard
              icon={GitMergeIcon}
              label="Merge commits"
              value={count(shape.merges)}
              hint={`${count(shape.nonMerges)} have a single parent`}
            />
            <StatCard
              icon={CalendarIcon}
              label="Repository age"
              value={humanDuration(ageSeconds)}
              hint="since the first recorded commit"
            />
            <StatCard
              icon={PulseIcon}
              label="Active days"
              value={count(activeDays)}
              hint="days carrying at least one commit"
            />
            <StatCard
              icon={GraphIcon}
              label="Average commits/day"
              value={average ?? "—"}
              hint={
                spanDays
                  ? `over the ${count(spanDays)} ${spanDays === 1 ? "day" : "days"} from first to latest commit`
                  : "no history to average"
              }
            />
          </StatGrid>

          <Text sx={{ fontSize: 0, color: "fg.muted" }}>
            Counted from the object store on each request. Nothing here is stored,
            estimated or cached.
          </Text>
        </Box>
      )}
    </AsyncBoundary>
  );
};

export default OverviewTab;
