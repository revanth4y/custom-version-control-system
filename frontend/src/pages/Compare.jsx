import { useCallback, useMemo } from "react";
import { useSearchParams } from "react-router-dom";
import { Box, Heading, Text } from "@primer/react";
import Octicon from "../components/common/Octicon";
import { ArrowRightIcon } from "@primer/octicons-react";

import PageContainer from "../components/layout/PageContainer";
import { AsyncBoundary } from "../components/common/states";
import Notice from "../components/common/Notice";
import BranchSelector from "../components/branch/BranchSelector";
import DiffViewer from "../components/diff/DiffViewer";
import { useAsync } from "../hooks/useAsync";
import { useRepository } from "../hooks/useRepository";
import { commitService } from "../services/commitService";

/**
 * Comparing two revisions.
 *
 * Both sides live in the query string, so a comparison is a link. They are real
 * revisions - branch names, HEAD, or full commit ids - resolved by the engine;
 * nothing here guesses at what exists.
 */
const Compare = () => {
  const { owner, name, head: repoHead, canWrite, reloadHead } = useRepository();
  const [params, setParams] = useSearchParams();

  const defaultRef = repoHead?.branch ?? "HEAD";
  const base = params.get("base") || defaultRef;
  const headRef = params.get("head") || defaultRef;

  const setSide = useCallback(
    (side) => (value) => {
      const next = new URLSearchParams(params);
      next.set(side, value);
      // Keep both sides explicit once either is chosen, so the link is complete.
      if (!next.get("base")) next.set("base", base);
      if (!next.get("head")) next.set("head", headRef);
      setParams(next);
    },
    [params, setParams, base, headRef],
  );

  const comparing = base !== headRef;

  const diff = useAsync(
    () =>
      comparing
        ? commitService.compare(owner, name, { base, head: headRef })
        : Promise.resolve({ base, head: headRef, filesChanged: 0, totalAdditions: 0, totalDeletions: 0, files: [] }),
    [owner, name, base, headRef, comparing],
  );

  const summary = useMemo(() => diff.data, [diff.data]);

  return (
    <PageContainer>
      <Box sx={{ mb: 3 }}>
        <Heading as="h2" sx={{ fontSize: 3, fontWeight: 600, mb: 1 }}>
          Compare
        </Heading>
        <Text sx={{ fontSize: 1, color: "fg.muted" }}>
          What it would take to bring the base up to the head.
        </Text>
      </Box>

      <Box
        sx={{
          display: "flex",
          alignItems: "center",
          gap: 3,
          flexWrap: "wrap",
          p: 3,
          mb: 3,
          border: "1px solid",
          borderColor: "border.default",
          borderRadius: 2,
          bg: "canvas.subtle",
        }}
      >
        <Side label="base">
          <BranchSelector
            owner={owner}
            name={name}
            currentRef={base}
            headBranch={repoHead?.branch}
            canWrite={canWrite}
            onRefChange={setSide("base")}
            onHeadChanged={reloadHead}
          />
        </Side>

        <Octicon icon={ArrowRightIcon} sx={{ color: "fg.muted", mt: 3, flexShrink: 0 }} />

        <Side label="head">
          <BranchSelector
            owner={owner}
            name={name}
            currentRef={headRef}
            headBranch={repoHead?.branch}
            canWrite={canWrite}
            onRefChange={setSide("head")}
            onHeadChanged={reloadHead}
          />
        </Side>
      </Box>

      {!comparing ? (
        <Notice variant="info">
          Base and head are the same revision. Choose two different ones to see what separates them.
        </Notice>
      ) : (
        <AsyncBoundary
          loading={diff.loading}
          error={diff.error}
          onRetry={diff.reload}
          loadingLabel="Comparing revisions"
          minHeight="220px"
        >
          {summary && (
            <>
              <Box sx={{ mb: 3 }}>
                <Text sx={{ fontSize: 1, color: "fg.muted" }}>
                  Comparing{" "}
                  <Text as="span" sx={{ fontFamily: "mono", color: "fg.default" }}>
                    {summary.base}
                  </Text>{" "}
                  with{" "}
                  <Text as="span" sx={{ fontFamily: "mono", color: "fg.default" }}>
                    {summary.head}
                  </Text>
                </Text>
              </Box>
              <DiffViewer result={summary} />
            </>
          )}
        </AsyncBoundary>
      )}
    </PageContainer>
  );
};

const Side = ({ label, children }) => (
  <Box sx={{ minWidth: 0 }}>
    <Text sx={{ display: "block", fontSize: 0, color: "fg.subtle", mb: 1 }}>{label}</Text>
    {children}
  </Box>
);

export default Compare;
