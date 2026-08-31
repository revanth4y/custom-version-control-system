import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { Box, Button, Heading, Label, Spinner, Text } from "@primer/react";
import { GitCommitIcon, GitMergeIcon } from "@primer/octicons-react";

import PageContainer from "../components/layout/PageContainer";
import { AsyncBoundary, EmptyState } from "../components/common/states";
import Notice from "../components/common/Notice";
import BranchSelector from "../components/branch/BranchSelector";
import CommitGraph from "../components/commit/CommitGraph";
import { createGraphMetrics } from "../components/commit/graphMetrics";
import { useAsync } from "../hooks/useAsync";
import { useRepository } from "../hooks/useRepository";
import { branchService } from "../services/branchService";
import { commitService } from "../services/commitService";
import { buildCommitGraph } from "../utils/commitGraph";
import { describeNode, indexRefs, refsFor } from "../utils/commitRefs";

const PAGE_SIZE = 50;

/**
 * The explorer's geometry.
 *
 * Taller rows and wider lanes than the history gutter, because here the graph
 * is the thing being looked at rather than a margin beside a list. Same shapes,
 * same arithmetic - only the numbers differ, so a node cannot land in one place
 * for the drawing and another for the row beside it.
 */
const EXPLORER_METRICS = createGraphMetrics({
  rowHeight: 56,
  laneWidth: 26,
  paddingX: 20,
  dotRadius: 5.5,
  mergeRadius: 7.5,
  boundaryLength: 18,
});

/**
 * The commit DAG, as the subject rather than the margin.
 *
 * Draws the same graph the commit history draws, from the same model, at a
 * larger scale: `buildCommitGraph` is reused unchanged, so ordering, lanes,
 * merges and boundaries mean exactly what they mean everywhere else. A second
 * layout algorithm would be a second answer to "what is the shape of this
 * history", and the two would eventually disagree.
 *
 * Pages accumulate. A parent that arrives in a later page stops being a
 * boundary and becomes a real edge, which is handled by rebuilding the graph
 * from the whole accumulated list rather than by patching the previous one.
 *
 * The drawing is decorative and hidden from assistive technology; the list
 * beside it is not. Each commit is a real focusable control whose accessible
 * name carries what the picture conveys visually - merge, root, refs, parents,
 * and whether a parent is still unloaded.
 */
const DagExplorer = () => {
  const { owner, name, head, canWrite, reloadHead } = useRepository();
  const params = useParams();
  const navigate = useNavigate();

  const refName = params.ref ? decodeURIComponent(params.ref) : head?.branch ?? "HEAD";

  const [pages, setPages] = useState([]);
  const [cursor, setCursor] = useState(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [moreError, setMoreError] = useState(null);

  const history = useAsync(
    () => commitService.historyPage(owner, name, { ref: refName, limit: PAGE_SIZE }),
    [owner, name, refName],
  );

  // Refs are a separate question from history and answered once per repository.
  const branches = useAsync(() => branchService.list(owner, name), [owner, name]);

  useEffect(() => {
    if (!history.data) {
      return;
    }
    setPages([history.data.commits]);
    setCursor(history.data.hasMore ? history.data.nextCursor : null);
    setMoreError(null);
  }, [history.data]);

  const commits = useMemo(() => pages.flat(), [pages]);
  const graph = useMemo(() => buildCommitGraph(commits), [commits]);
  const refIndex = useMemo(() => indexRefs(branches.data, head), [branches.data, head]);

  const reachedEnd = cursor === null;

  const loadMore = useCallback(async () => {
    if (!cursor || loadingMore) {
      return;
    }
    setLoadingMore(true);
    setMoreError(null);
    try {
      const next = await commitService.historyPage(owner, name, {
        ref: refName,
        limit: PAGE_SIZE,
        cursor,
      });
      setPages((current) => [...current, next.commits]);
      setCursor(next.hasMore ? next.nextCursor : null);
    } catch (error) {
      // What is already drawn stays drawn; only the extension failed.
      setMoreError(error);
    } finally {
      setLoadingMore(false);
    }
  }, [cursor, loadingMore, owner, name, refName]);

  const changeRef = useCallback(
    (branch) => navigate(`/${owner}/${name}/graph/${encodeURIComponent(branch)}`),
    [navigate, owner, name],
  );

  const openCommit = useCallback(
    (sha) => navigate(`/${owner}/${name}/commit/${sha}`),
    [navigate, owner, name],
  );

  const hasNoCommits = !head?.commit;
  const mergeCount = graph.rows.filter((node) => node.isMerge).length;

  return (
    <PageContainer>
      <Box
        sx={{ display: "flex", alignItems: "flex-start", gap: 3, flexWrap: "wrap", mb: 3 }}
      >
        <BranchSelector
          owner={owner}
          name={name}
          currentRef={refName}
          headBranch={head?.branch}
          canWrite={canWrite}
          onRefChange={changeRef}
          onHeadChanged={reloadHead}
        />
        <Box sx={{ flex: 1, minWidth: 0, pt: 1 }}>
          <Heading as="h2" sx={{ fontSize: 2, fontWeight: 600 }}>
            Graph
          </Heading>
          <Text sx={{ fontSize: 0, color: "fg.muted" }}>
            Every line is a parent link taken from the commit itself.
          </Text>
        </Box>
      </Box>

      {hasNoCommits ? (
        <Panel>
          <EmptyState
            icon={GitCommitIcon}
            title="No commits yet"
            message="Nothing has been committed to this repository, so there is no graph to draw."
            minHeight="220px"
          />
        </Panel>
      ) : (
        <AsyncBoundary
          loading={history.loading && commits.length === 0}
          error={history.error}
          onRetry={history.reload}
          loadingLabel="Loading graph"
          minHeight="260px"
        >
          {graph.boundaries.length > 0 && (
            <Box sx={{ mb: 3 }}>
              <Notice variant="info">
                Some commits have parents that are not loaded. Those links end in a faded stub
                rather than a commit.
              </Notice>
            </Box>
          )}

          <Panel>
            {/* One horizontally scrolling strip, so a wide graph never widens
                the page itself. The drawing and the rows must scroll together
                or the nodes drift away from the commits they belong to. */}
            <Box sx={{ overflowX: "auto" }}>
              <Box sx={{ display: "flex", alignItems: "flex-start", minWidth: "min-content" }}>
                <CommitGraph graph={graph} metrics={EXPLORER_METRICS} />

                <Box
                  as="ul"
                  aria-label={`Commit graph for ${refName}`}
                  sx={{ listStyle: "none", m: 0, p: 0, flex: 1, minWidth: "320px" }}
                >
                  {graph.rows.map((node) => (
                    <GraphRow
                      key={node.sha}
                      node={node}
                      refs={refsFor(refIndex, node.sha)}
                      onOpen={openCommit}
                    />
                  ))}
                </Box>
              </Box>
            </Box>
          </Panel>

          <Box
            sx={{
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              gap: 3,
              flexWrap: "wrap",
              mt: 3,
            }}
          >
            <Text sx={{ fontSize: 0, color: "fg.muted" }}>
              {graph.rows.length} {graph.rows.length === 1 ? "commit" : "commits"}
              {mergeCount > 0 && `, ${mergeCount} ${mergeCount === 1 ? "merge" : "merges"}`}
              {reachedEnd && " — the whole history from here"}
            </Text>

            {moreError && (
              <Notice variant="danger">
                Could not load more history. The graph above is unaffected.
              </Notice>
            )}

            {!reachedEnd && (
              <Button
                onClick={loadMore}
                disabled={loadingMore}
                leadingVisual={loadingMore ? undefined : GitCommitIcon}
              >
                {loadingMore ? (
                  <Box sx={{ display: "inline-flex", alignItems: "center", gap: 2 }}>
                    <Spinner size="small" />
                    Loading
                  </Box>
                ) : (
                  "Load more history"
                )}
              </Button>
            )}
          </Box>
        </AsyncBoundary>
      )}
    </PageContainer>
  );
};

/**
 * One commit in the graph.
 *
 * A button rather than a decorated row, because it does something: it opens the
 * commit. That makes it reachable by keyboard and announced as activatable
 * without any aria- attribute pretending it is.
 *
 * The accessible name is built from the model, not from what is on screen. The
 * ring that means "merge" and the faded stub that means "parent not loaded yet"
 * are shapes; read aloud they are nothing at all, so both are said in words.
 */
const GraphRow = ({ node, refs, onOpen }) => {
  const { rowHeight } = EXPLORER_METRICS;
  const subject = (node.commit?.message ?? "").split("\n")[0].trim();

  return (
    <Box as="li" sx={{ height: `${rowHeight}px` }}>
      <Box
        as="button"
        type="button"
        onClick={() => onOpen(node.sha)}
        aria-label={describeNode(node, refs)}
        sx={{
          display: "flex",
          alignItems: "center",
          gap: 2,
          width: "100%",
          height: "100%",
          px: 3,
          border: 0,
          bg: "transparent",
          color: "fg.default",
          textAlign: "left",
          cursor: "pointer",
          font: "inherit",
          "&:hover": { bg: "canvas.default" },
          "&:focus-visible": { outline: "2px solid", outlineColor: "accent.fg", outlineOffset: "-2px" },
        }}
      >
        {node.isMerge && (
          <Box aria-hidden="true" sx={{ display: "inline-flex", color: "fg.muted", flexShrink: 0 }}>
            <GitMergeIcon size={16} />
          </Box>
        )}

        <Text
          sx={{
            fontSize: 1,
            fontWeight: 500,
            overflow: "hidden",
            textOverflow: "ellipsis",
            whiteSpace: "nowrap",
            minWidth: 0,
          }}
        >
          {subject || node.shortSha}
        </Text>

        {refs.map((ref) => (
          <Label
            key={ref.name}
            aria-hidden="true"
            variant={ref.isHead ? "accent" : "secondary"}
            sx={{ flexShrink: 0 }}
          >
            {ref.name}
          </Label>
        ))}

        <Text
          aria-hidden="true"
          sx={{ ml: "auto", fontFamily: "mono", fontSize: 0, color: "fg.muted", flexShrink: 0 }}
        >
          {node.shortSha}
        </Text>
      </Box>
    </Box>
  );
};

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

export default DagExplorer;
