import { Box, Label, Text } from "@primer/react";
import { GitBranchIcon, ServerIcon, ShieldLockIcon, TagIcon } from "@primer/octicons-react";

import StatCard, { StatGrid } from "./StatCard";
import { AsyncBoundary } from "../common/states";
import { count, humanDuration, shortId } from "./format";
import { formatAbsoluteTime, formatRelativeTime } from "../../utils/dates";

/**
 * Every name the repository keeps, and where each one points.
 *
 * Branch tips carry no recorded update time — the engine stores a reference as
 * a name and an object id, nothing else — so movement is shown as distance from
 * HEAD rather than as a timestamp this page would have to invent. Tags do carry
 * a tagging time when they are annotated, and that is shown where it exists.
 */
const RefsTab = ({ refs, branches, tags }) => {
  const loading = refs.loading || branches.loading || tags.loading;
  const error = refs.error || branches.error || tags.error;

  const composition = refs.data;
  const branchList = branches.data?.branches ?? [];
  const tagSummary = tags.data;

  return (
    <AsyncBoundary
      loading={loading}
      error={error}
      onRetry={() => {
        refs.reload();
        branches.reload();
        tags.reload();
      }}
      loadingLabel="Loading references"
      minHeight="240px"
    >
      {composition && tagSummary && (
        <Box sx={{ display: "flex", flexDirection: "column", gap: 4 }}>
          <StatGrid>
            <StatCard icon={GitBranchIcon} label="Branches" value={count(composition.branches)} />
            <StatCard
              icon={ServerIcon}
              label="Remote branches"
              value={count(composition.remoteTrackingRefs)}
              hint={`across ${count(composition.remotes)} ${composition.remotes === 1 ? "remote" : "remotes"}`}
            />
            <StatCard
              icon={TagIcon}
              label="Tags"
              value={count(composition.tags)}
              hint={`${count(tagSummary.annotated)} annotated, ${count(tagSummary.lightweight)} lightweight`}
            />
            <StatCard
              icon={GitBranchIcon}
              label="HEAD"
              value={composition.headAttached ? (composition.headBranch ?? "attached") : "detached"}
              hint={composition.headAttached ? "the checked-out branch" : "not on a branch"}
            />
            <StatCard
              icon={GitBranchIcon}
              label="Default branch"
              value={composition.headBranch ?? "—"}
              hint="what a fresh clone checks out"
            />
            <StatCard
              icon={ShieldLockIcon}
              label="Kept alive by tags alone"
              value={count(composition.commitsOnlyTagsProtect)}
              hint="commits no branch reaches"
            />
            <StatCard
              icon={TagIcon}
              label="Latest tag"
              value={tagSummary.lastTagged ? formatRelativeTime(tagSummary.lastTagged) : "—"}
              hint={tagSummary.lastTagged ? formatAbsoluteTime(tagSummary.lastTagged) : "no annotated tags"}
            />
            <StatCard
              icon={TagIcon}
              label="Typical gap between tags"
              value={
                tagSummary.medianIntervalSeconds === null ||
                tagSummary.medianIntervalSeconds === undefined
                  ? "—"
                  : humanDuration(tagSummary.medianIntervalSeconds)
              }
              hint="median, annotated tags only"
            />
          </StatGrid>

          <Section title="Branches" subtitle="Distance from HEAD">
            {branchList.length === 0 ? (
              <Empty>No branches.</Empty>
            ) : (
              branchList.map((branch) => (
                <Row key={branch.name}>
                  <Box sx={{ flex: "1 1 200px", minWidth: 0, display: "flex", alignItems: "center", gap: 2 }}>
                    <Text sx={{ fontSize: 1, fontWeight: 600, wordBreak: "break-all" }}>{branch.name}</Text>
                    {branch.current && <Label variant="accent">HEAD</Label>}
                    {!branch.related && <Label variant="attention">unrelated</Label>}
                  </Box>
                  <Text sx={{ fontFamily: "mono", fontSize: 0, color: "fg.muted", minWidth: "110px" }}>
                    {shortId(branch.tip)}
                  </Text>
                  <Text sx={{ fontSize: 0, color: "fg.muted", minWidth: "140px" }}>
                    {count(branch.ahead)} ahead · {count(branch.behind)} behind
                  </Text>
                </Row>
              ))
            )}
          </Section>

          <Section title="Tags" subtitle={`${count(tagSummary.total)} total`}>
            {(tagSummary.tags ?? []).length === 0 ? (
              <Empty>No tags.</Empty>
            ) : (
              tagSummary.tags.map((tag) => (
                <Row key={tag.name}>
                  <Box sx={{ flex: "1 1 200px", minWidth: 0, display: "flex", alignItems: "center", gap: 2 }}>
                    <Text sx={{ fontSize: 1, fontWeight: 600, wordBreak: "break-all" }}>{tag.name}</Text>
                    <Label variant={tag.annotated ? "accent" : "secondary"}>
                      {tag.annotated ? "annotated" : "lightweight"}
                    </Label>
                  </Box>
                  <Text sx={{ fontFamily: "mono", fontSize: 0, color: "fg.muted", minWidth: "110px" }}>
                    {shortId(tag.commit)}
                  </Text>
                  <Text sx={{ fontSize: 0, color: "fg.muted", minWidth: "140px" }}>
                    {tag.taggedAt ? formatRelativeTime(tag.taggedAt) : "no tagging time recorded"}
                  </Text>
                </Row>
              ))
            )}
          </Section>

          {(tagSummary.withoutRelease ?? []).length > 0 && (
            <Section title="Tags without a release" subtitle={count(tagSummary.withoutRelease.length)}>
              <Box sx={{ px: 3, py: 3, display: "flex", flexWrap: "wrap", gap: 2 }}>
                {tagSummary.withoutRelease.map((name) => (
                  <Label key={name} variant="secondary">
                    {name}
                  </Label>
                ))}
              </Box>
            </Section>
          )}
        </Box>
      )}
    </AsyncBoundary>
  );
};

const Section = ({ title, subtitle, children }) => (
  <Box sx={{ border: "1px solid", borderColor: "border.default", borderRadius: 2, overflow: "hidden" }}>
    <Box
      sx={{
        px: 3,
        py: 2,
        bg: "canvas.subtle",
        borderBottom: "1px solid",
        borderColor: "border.default",
        display: "flex",
        justifyContent: "space-between",
        gap: 2,
        flexWrap: "wrap",
      }}
    >
      <Text sx={{ fontSize: 1, fontWeight: 600 }}>{title}</Text>
      {subtitle && <Text sx={{ fontSize: 0, color: "fg.muted" }}>{subtitle}</Text>}
    </Box>
    {children}
  </Box>
);

const Row = ({ children }) => (
  <Box
    sx={{
      display: "flex",
      alignItems: "center",
      gap: 3,
      px: 3,
      py: 2,
      borderTop: "1px solid",
      borderColor: "border.muted",
      ":first-of-type": { borderTop: 0 },
      flexWrap: "wrap",
    }}
  >
    {children}
  </Box>
);

const Empty = ({ children }) => (
  <Text sx={{ display: "block", px: 3, py: 3, fontSize: 0, color: "fg.muted" }}>{children}</Text>
);

export default RefsTab;
