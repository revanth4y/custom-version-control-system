import { useState } from "react";
import { Box, Heading, Text } from "@primer/react";
import { GraphIcon } from "@primer/octicons-react";

import PageContainer from "../components/layout/PageContainer";
import { EmptyState } from "../components/common/states";
import ActivityTab from "../components/insights/ActivityTab";
import CommitsTab from "../components/insights/CommitsTab";
import ContributorsTab from "../components/insights/ContributorsTab";
import HealthTab from "../components/insights/HealthTab";
import OverviewTab from "../components/insights/OverviewTab";
import RefsTab from "../components/insights/RefsTab";
import { useAsync } from "../hooks/useAsync";
import { useRepository } from "../hooks/useRepository";
import {
  useActivityInsights,
  useBranchInsights,
  useCommitInsights,
  useCommitSeries,
  useContributorInsights,
  useInsightsRange,
  useRefInsights,
  useRepositoryHealth,
  useTagInsights,
} from "../hooks/useInsights";
import { insightsService } from "../services/insightsService";

const TABS = [
  { key: "overview", label: "Overview" },
  { key: "activity", label: "Activity" },
  { key: "commits", label: "Commits" },
  { key: "contributors", label: "Contributors" },
  { key: "refs", label: "Refs" },
  { key: "health", label: "Health" },
];

const DEFAULT_RANGE = { from: "", to: "", bucket: "day" };

/**
 * What the repository adds up to.
 *
 * Every figure comes from an endpoint that recomputes it from the object store
 * on each call rather than reading a stored tally — so nothing here can disagree
 * with the history it describes, and nothing is estimated or filled in.
 *
 * Only the visible tab fetches. Six surfaces loading eleven endpoints at once
 * would make the cheap views wait on the expensive ones for data most visits
 * never open, and the Health tab in particular must cost nothing until asked.
 */
const Insights = () => {
  const { owner, name, head } = useRepository();
  const [view, setView] = useState("overview");

  const hasHistory = Boolean(head?.commit);

  const activityRange = useInsightsRange();
  const contributorRange = useInsightsRange();

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
        <EmptyState
          icon={GraphIcon}
          title="Nothing to measure yet"
          message="This repository has no commits, so there are no statistics to report."
          minHeight="220px"
        />
      ) : (
        <>
          <Box
            role="tablist"
            aria-label="Insights"
            sx={{
              display: "flex",
              gap: 1,
              mb: 3,
              borderBottom: "1px solid",
              borderColor: "border.default",
              // Narrow screens scroll the tab strip rather than the page: the
              // six names cannot fit at 375px, and wrapping them would push the
              // content down by a whole row.
              overflowX: "auto",
              whiteSpace: "nowrap",
            }}
          >
            {TABS.map((tab) => {
              const active = view === tab.key;
              return (
                <Box
                  key={tab.key}
                  as="button"
                  type="button"
                  role="tab"
                  aria-selected={active}
                  onClick={() => setView(tab.key)}
                  sx={{
                    appearance: "none",
                    background: "transparent",
                    border: 0,
                    borderBottom: "2px solid",
                    borderColor: active ? "accent.emphasis" : "transparent",
                    color: active ? "fg.default" : "fg.muted",
                    cursor: "pointer",
                    flex: "0 0 auto",
                    fontSize: 1,
                    fontWeight: active ? 600 : 400,
                    px: 3,
                    py: 2,
                  }}
                >
                  {tab.label}
                </Box>
              );
            })}
          </Box>

          {view === "overview" && <OverviewPanel owner={owner} name={name} />}
          {view === "activity" && <ActivityPanel owner={owner} name={name} range={activityRange} />}
          {view === "commits" && <CommitsPanel owner={owner} name={name} />}
          {view === "contributors" && (
            <ContributorsPanel owner={owner} name={name} range={contributorRange} />
          )}
          {view === "refs" && <RefsPanel owner={owner} name={name} />}
          {view === "health" && <HealthPanel owner={owner} name={name} />}
        </>
      )}
    </PageContainer>
  );
};

/*
 * Each panel is a component so that its hooks mount with the tab and unmount
 * with it. Calling the hooks in the page body instead would fetch every surface
 * on arrival — including for a repository with no commits at all, which has
 * nothing to report and should ask the server for nothing.
 */

const useOverview = (owner, name) =>
  useAsync(() => insightsService.forRepository(owner, name), [owner, name]);

const OverviewPanel = ({ owner, name }) => {
  const overview = useOverview(owner, name);
  const dag = useCommitInsights(owner, name);
  const refs = useRefInsights(owner, name);
  return <OverviewTab overview={overview} dag={dag} refs={refs} />;
};

const ActivityPanel = ({ owner, name, range }) => {
  const series = useCommitSeries(owner, name, range.applied);
  const activity = useActivityInsights(owner, name, range.applied);
  return <ActivityTab range={range} series={series} activity={activity} />;
};

const CommitsPanel = ({ owner, name }) => {
  const dag = useCommitInsights(owner, name);
  const overview = useOverview(owner, name);
  const timeline = useCommitSeries(owner, name, DEFAULT_RANGE);
  return <CommitsTab dag={dag} overview={overview} timeline={timeline} />;
};

const ContributorsPanel = ({ owner, name, range }) => {
  const contributors = useContributorInsights(owner, name, range.applied);
  return <ContributorsTab range={range} contributors={contributors} />;
};

const RefsPanel = ({ owner, name }) => {
  const refs = useRefInsights(owner, name);
  const branches = useBranchInsights(owner, name);
  const tags = useTagInsights(owner, name);
  return <RefsTab refs={refs} branches={branches} tags={tags} />;
};

const HealthPanel = ({ owner, name }) => {
  const health = useRepositoryHealth(owner, name);
  return <HealthTab {...health} />;
};

export default Insights;
