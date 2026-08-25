import { useMemo } from "react";
import { Box, Heading, Text, Octicon } from "@primer/react";
import { DatabaseIcon, FileIcon, GitBranchIcon, GitCommitIcon, GraphIcon } from "@primer/octicons-react";

import PageContainer from "../components/layout/PageContainer";
import { AsyncBoundary, EmptyState } from "../components/common/states";
import IdentityAvatar from "../components/common/IdentityAvatar";
import { useAsync } from "../hooks/useAsync";
import { useRepository } from "../hooks/useRepository";
import { insightsService } from "../services/insightsService";
import { formatAbsoluteTime } from "../utils/dates";
import { brand } from "../theme/gitforge";

/**
 * What the repository adds up to.
 *
 * Every figure comes from the endpoint, which recomputes them from the object
 * store on each call rather than reading a stored tally - so nothing here can
 * disagree with the history it describes. Nothing is estimated or filled in.
 */
const Insights = () => {
  const { owner, name, head } = useRepository();
  const insights = useAsync(() => insightsService.forRepository(owner, name), [owner, name]);

  const data = insights.data;
  const hasHistory = Boolean(head?.commit);

  return (
    <PageContainer>
      <Box sx={{ mb: 3 }}>
        <Heading as="h2" sx={{ fontSize: 3, fontWeight: 600, mb: 1 }}>
          Insights
        </Heading>
        <Text sx={{ fontSize: 1, color: "fg.muted" }}>
          Counted from the object store itself, every time this page is opened.
        </Text>
      </Box>

      {!hasHistory ? (
        <Panel>
          <EmptyState
            icon={GraphIcon}
            title="Nothing to measure yet"
            message="This repository has no commits, so there are no statistics to report."
            minHeight="220px"
          />
        </Panel>
      ) : (
        <AsyncBoundary
          loading={insights.loading}
          error={insights.error}
          onRetry={insights.reload}
          loadingLabel="Computing insights"
          minHeight="240px"
        >
          {data && (
            <Box sx={{ display: "flex", flexDirection: "column", gap: 4 }}>
              <Box
                sx={{
                  display: "grid",
                  gridTemplateColumns: ["repeat(2, minmax(0, 1fr))", "repeat(2, minmax(0, 1fr))", "repeat(4, minmax(0, 1fr))"],
                  gap: 3,
                }}
              >
                <Stat icon={GitCommitIcon} label="commits" value={data.commits} hint="reachable from any branch" />
                <Stat icon={GitBranchIcon} label="branches" value={data.branches} hint="references in this repository" />
                <Stat icon={FileIcon} label="files" value={data.files} hint="in the tree HEAD points at" />
                <Stat icon={DatabaseIcon} label="stored objects" value={data.storedObjects} hint="blobs, trees and commits" />
              </Box>

              <Section title="Contributors" count={data.contributors.length}>
                <Contributors contributors={data.contributors} total={data.commits} />
              </Section>

              <Section title="Activity" count={data.activity.length} unit="day">
                <Activity activity={data.activity} />
              </Section>
            </Box>
          )}
        </AsyncBoundary>
      )}
    </PageContainer>
  );
};

const Stat = ({ icon, label, value, hint }) => (
  <Box
    sx={{
      border: "1px solid",
      borderColor: "border.default",
      borderRadius: 2,
      bg: "canvas.subtle",
      px: 3,
      py: 3,
      minWidth: 0,
    }}
  >
    <Box sx={{ display: "flex", alignItems: "center", gap: 2, mb: 1 }}>
      <Octicon icon={icon} size={14} sx={{ color: "fg.subtle" }} />
      <Text sx={{ fontSize: 0, color: "fg.muted" }}>{label}</Text>
    </Box>
    <Text sx={{ display: "block", fontSize: 4, fontWeight: 600, lineHeight: 1.1 }}>
      {value.toLocaleString()}
    </Text>
    <Text sx={{ display: "block", fontSize: 0, color: "fg.subtle", mt: 1 }}>{hint}</Text>
  </Box>
);

/**
 * Who wrote the commits.
 *
 * Identity is the email, which is how the engine groups them: one person may
 * commit under several display names, but the address is what identifies them.
 */
const Contributors = ({ contributors, total }) => {
  if (contributors.length === 0) {
    return <Text sx={{ fontSize: 0, color: "fg.subtle" }}>No contributors recorded.</Text>;
  }

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 3 }}>
      {contributors.map((contributor) => {
        const share = total > 0 ? (contributor.commits / total) * 100 : 0;
        return (
          <Box key={contributor.email} sx={{ minWidth: 0 }}>
            <Box sx={{ display: "flex", alignItems: "center", gap: 2, mb: 1, minWidth: 0 }}>
              <IdentityAvatar username={contributor.name || contributor.email} size={20} />
              <Text sx={{ fontSize: 1, fontWeight: 600, overflowWrap: "anywhere" }}>
                {contributor.name}
              </Text>
              <Text
                sx={{
                  fontSize: 0,
                  color: "fg.subtle",
                  overflow: "hidden",
                  textOverflow: "ellipsis",
                  whiteSpace: "nowrap",
                  minWidth: 0,
                }}
                title={contributor.email}
              >
                {contributor.email}
              </Text>
              <Text sx={{ ml: "auto", fontSize: 0, color: "fg.muted", flexShrink: 0 }}>
                {contributor.commits} {contributor.commits === 1 ? "commit" : "commits"}
              </Text>
            </Box>
            <Box
              aria-hidden="true"
              sx={{ height: "6px", borderRadius: 999, bg: "border.muted", overflow: "hidden" }}
            >
              <Box sx={{ width: `${share}%`, height: "100%", bg: "accent.emphasis" }} />
            </Box>
          </Box>
        );
      })}
    </Box>
  );
};

/**
 * Commits per day, over the days that actually have them.
 *
 * Unlike the contribution calendar, this endpoint reports only days with
 * activity - it does not fill the gaps. Drawing it as a calendar would invent
 * the days in between and claim they were empty, when the endpoint never said
 * so. A bar per reported day says exactly what is known and no more.
 */
const Activity = ({ activity }) => {
  const max = useMemo(
    () => activity.reduce((most, day) => Math.max(most, day.count), 0),
    [activity],
  );

  if (activity.length === 0) {
    return <Text sx={{ fontSize: 0, color: "fg.subtle" }}>No commit activity recorded.</Text>;
  }

  const first = activity[0];
  const last = activity[activity.length - 1];

  return (
    <Box>
      <Box sx={{ overflowX: "auto", pb: 2 }}>
        <Box
          sx={{
            display: "flex",
            alignItems: "flex-end",
            gap: 1,
            height: "120px",
            minWidth: "min-content",
          }}
        >
          {activity.map((day) => (
            <Box
              key={day.date}
              title={`${day.count} ${day.count === 1 ? "commit" : "commits"} on ${day.date}`}
              sx={{
                // Grows to share the width when there are few days, so a young
                // repository does not draw a thumbnail chart in the corner of a
                // wide card; capped so a single day is a bar and not a slab.
                // Many days hit the 14px basis instead and the row scrolls.
                flex: "1 1 14px",
                minWidth: "14px",
                maxWidth: "48px",
                // A day with commits is never invisible, however small its share.
                height: `${Math.max((day.count / max) * 100, 6)}%`,
                borderRadius: 1,
                bg: "accent.emphasis",
                backgroundImage: `linear-gradient(${brand.accent}, ${brand.accentHover})`,
              }}
            />
          ))}
        </Box>
      </Box>

      <Text sx={{ display: "block", fontSize: 0, color: "fg.subtle", mt: 2 }}>
        {activity.length === 1 ? (
          <>All activity on {first.date}.</>
        ) : (
          <>
            <Text as="span" title={formatAbsoluteTime(first.date)}>{first.date}</Text>
            {" to "}
            <Text as="span" title={formatAbsoluteTime(last.date)}>{last.date}</Text>
            {" · only days with commits are shown · "}
            {/* Without this a chart where every day scored the same is a row of
                equal bars with nothing to read them against. */}
            {`busiest day ${max} ${max === 1 ? "commit" : "commits"}`}
          </>
        )}
      </Text>
    </Box>
  );
};

const Section = ({ title, count, unit, children }) => (
  <Box sx={{ minWidth: 0 }}>
    <Box sx={{ display: "flex", alignItems: "baseline", gap: 2, mb: 3 }}>
      <Heading as="h3" sx={{ fontSize: 2, fontWeight: 600 }}>
        {title}
      </Heading>
      <Text sx={{ fontSize: 0, color: "fg.subtle" }}>
        {count} {unit ? `${unit}${count === 1 ? "" : "s"}` : ""}
      </Text>
    </Box>
    <Box
      sx={{
        border: "1px solid",
        borderColor: "border.default",
        borderRadius: 2,
        bg: "canvas.subtle",
        px: 3,
        py: 3,
        minWidth: 0,
      }}
    >
      {children}
    </Box>
  </Box>
);

const Panel = ({ children }) => (
  <Box
    sx={{
      bg: "canvas.subtle",
      border: "1px solid",
      borderColor: "border.default",
      borderRadius: 2,
      overflow: "hidden",
    }}
  >
    {children}
  </Box>
);

export default Insights;
