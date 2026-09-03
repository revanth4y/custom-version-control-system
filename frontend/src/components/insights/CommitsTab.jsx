import { useMemo } from "react";
import { Box, Text } from "@primer/react";
import {
  ClockIcon,
  FlameIcon,
  GitCommitIcon,
  GitMergeIcon,
  HistoryIcon,
  StackIcon,
} from "@primer/octicons-react";

import BarSeries from "./BarSeries";
import StatCard, { StatGrid } from "./StatCard";
import { AsyncBoundary } from "../common/states";
import { count, humanDuration, percent, shortId } from "./format";
import { formatAbsoluteTime } from "../../utils/dates";

/**
 * The busiest day and the longest run of consecutive days.
 *
 * Both are computed from the all-time list of days that carry commits. Absence
 * from that list means zero, which is what breaks a streak — so a gap of one
 * calendar day ends the run, and the arithmetic stays in whole UTC days to
 * match how the backend buckets them.
 */
function summarise(activity) {
  if (!activity || activity.length === 0) {
    return { busiest: null, streak: 0, streakEnd: null };
  }

  const days = [...activity].sort((a, b) => a.date.localeCompare(b.date));

  let busiest = days[0];
  for (const day of days) {
    if (day.count > busiest.count) busiest = day;
  }

  let longest = 1;
  let running = 1;
  let end = days[0].date;
  let runningEnd = days[0].date;

  for (let index = 1; index < days.length; index += 1) {
    const previous = Date.parse(`${days[index - 1].date}T00:00:00Z`);
    const current = Date.parse(`${days[index].date}T00:00:00Z`);
    running = current - previous === 86_400_000 ? running + 1 : 1;
    runningEnd = days[index].date;
    if (running > longest) {
      longest = running;
      end = runningEnd;
    }
  }

  return { busiest, streak: longest, streakEnd: end };
}

const CommitsTab = ({ dag, overview, timeline }) => {
  const streaks = useMemo(() => summarise(overview.data?.activity), [overview.data]);

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 3 }}>
      <AsyncBoundary
        loading={timeline.loading}
        error={timeline.error}
        onRetry={timeline.reload}
        loadingLabel="Loading the timeline"
        minHeight="200px"
      >
        {timeline.data && (
          <Box
            sx={{
              border: "1px solid",
              borderColor: "border.default",
              borderRadius: 2,
              p: 3,
              bg: "canvas.default",
            }}
          >
            <Text sx={{ fontSize: 1, fontWeight: 600, display: "block", mb: 3 }}>
              Commit timeline
            </Text>
            <BarSeries points={timeline.data.points} label="Commits" />
          </Box>
        )}
      </AsyncBoundary>

      <AsyncBoundary
        loading={dag.loading || overview.loading}
        error={dag.error || overview.error}
        onRetry={() => {
          dag.reload();
          overview.reload();
        }}
        loadingLabel="Loading commit statistics"
        minHeight="200px"
      >
        {dag.data && (
          <>
            <StatGrid>
              <StatCard
                icon={GitCommitIcon}
                label="Normal commits"
                value={count(dag.data.nonMerges)}
                hint={`${percent(dag.data.nonMerges, dag.data.commits)} of all commits`}
              />
              <StatCard
                icon={GitMergeIcon}
                label="Merge commits"
                value={count(dag.data.merges)}
                hint={`${percent(dag.data.merges, dag.data.commits)} of all commits`}
              />
              <StatCard
                icon={FlameIcon}
                label="Busiest day"
                value={streaks.busiest ? count(streaks.busiest.count) : "—"}
                hint={streaks.busiest ? `on ${streaks.busiest.date}` : "no commits recorded"}
              />
              <StatCard
                icon={HistoryIcon}
                label="Longest streak"
                value={streaks.streak ? `${count(streaks.streak)} d` : "—"}
                hint={streaks.streakEnd ? `ending ${streaks.streakEnd}` : "no commits recorded"}
              />
              <StatCard
                icon={ClockIcon}
                label="First commit"
                value={dag.data.earliestCommit ? formatAbsoluteTime(dag.data.earliestCommit) : "—"}
              />
              <StatCard
                icon={ClockIcon}
                label="Latest commit"
                value={dag.data.latestCommit ? formatAbsoluteTime(dag.data.latestCommit) : "—"}
                hint={
                  dag.data.historyDurationSeconds === null ||
                  dag.data.historyDurationSeconds === undefined
                    ? undefined
                    : `${humanDuration(dag.data.historyDurationSeconds)} of history`
                }
              />
              <StatCard
                icon={StackIcon}
                label="Longest chain"
                value={count(dag.data.maxDepth)}
                hint="commits from a root to the deepest tip"
              />
              <StatCard
                icon={GitMergeIcon}
                label="Most parents"
                value={count(dag.data.maxParents)}
                hint={`${count(dag.data.roots)} root ${dag.data.roots === 1 ? "commit" : "commits"}`}
              />
            </StatGrid>

            {dag.data.rootCommits?.length > 0 && (
              <Box sx={{ mt: 3 }}>
                <Text sx={{ fontSize: 1, fontWeight: 600, display: "block", mb: 2 }}>
                  Root commits
                </Text>
                <Box sx={{ display: "flex", flexWrap: "wrap", gap: 2 }}>
                  {dag.data.rootCommits.map((sha) => (
                    <Text
                      key={sha}
                      sx={{
                        fontFamily: "mono",
                        fontSize: 0,
                        color: "fg.muted",
                        border: "1px solid",
                        borderColor: "border.default",
                        borderRadius: 2,
                        px: 2,
                        py: 1,
                      }}
                    >
                      {shortId(sha)}
                    </Text>
                  ))}
                </Box>
              </Box>
            )}
          </>
        )}
      </AsyncBoundary>
    </Box>
  );
};

export default CommitsTab;
