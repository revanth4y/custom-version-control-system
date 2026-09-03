import { Box, Text } from "@primer/react";
import {
  GitCommitIcon,
  GitMergeIcon,
  IssueClosedIcon,
  IssueOpenedIcon,
  PeopleIcon,
  RocketIcon,
  TagIcon,
} from "@primer/octicons-react";

import BarSeries from "./BarSeries";
import RangeControls from "./RangeControls";
import StatCard, { StatGrid } from "./StatCard";
import { AsyncBoundary } from "../common/states";
import { count } from "./format";

/**
 * What happened in a window of time.
 *
 * The chart draws every bucket the API returned, including the empty ones, so a
 * quiet fortnight looks quiet rather than disappearing. Both the window and the
 * grain come from the server's own defaults until the reader changes them.
 */
const ActivityTab = ({ range, series, activity }) => (
  <Box>
    <RangeControls
      from={range.draft.from}
      to={range.draft.to}
      bucket={range.draft.bucket}
      onFrom={range.setFrom}
      onTo={range.setTo}
      onBucket={range.setBucket}
      onApply={range.apply}
      onReset={range.reset}
      pending={series.loading || activity.loading}
    />

    <AsyncBoundary
      loading={series.loading}
      error={series.error}
      onRetry={series.reload}
      loadingLabel="Loading activity"
      minHeight="220px"
    >
      {series.data && (
        <Box
          sx={{
            border: "1px solid",
            borderColor: "border.default",
            borderRadius: 2,
            p: 3,
            mb: 3,
            bg: "canvas.default",
          }}
        >
          <Box sx={{ display: "flex", justifyContent: "space-between", flexWrap: "wrap", gap: 2, mb: 3 }}>
            <Text sx={{ fontSize: 1, fontWeight: 600 }}>
              Commits per {series.data.bucket === "week" ? "week" : "day"}
            </Text>
            <Text sx={{ fontSize: 0, color: "fg.muted" }}>
              {series.data.from} to {series.data.to}
            </Text>
          </Box>

          <BarSeries points={series.data.points} label="Commits" />
        </Box>
      )}
    </AsyncBoundary>

    <AsyncBoundary
      loading={activity.loading}
      error={activity.error}
      onRetry={activity.reload}
      loadingLabel="Loading totals"
      minHeight="160px"
    >
      {activity.data && (
        <StatGrid>
          <StatCard icon={GitCommitIcon} label="Commits" value={count(activity.data.commits)} />
          <StatCard icon={GitMergeIcon} label="Merges" value={count(activity.data.merges)} />
          <StatCard icon={PeopleIcon} label="Contributors" value={count(activity.data.contributors)} />
          <StatCard icon={IssueOpenedIcon} label="Issues opened" value={count(activity.data.issuesOpened)} />
          <StatCard
            icon={IssueClosedIcon}
            label="Issues closed"
            value={count(activity.data.issuesClosed)}
            hint="recorded from this version onward"
          />
          <StatCard icon={RocketIcon} label="Releases published" value={count(activity.data.releasesPublished)} />
          <StatCard icon={TagIcon} label="Tags created" value={count(activity.data.tagsCreated)} />
        </StatGrid>
      )}
    </AsyncBoundary>
  </Box>
);

export default ActivityTab;
