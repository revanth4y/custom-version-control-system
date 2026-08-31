import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import RouterLink from "../components/common/RouterLink";
import { Box, Button, Heading, Link, Text, Spinner } from "@primer/react";
import { FileIcon, GitCommitIcon, GitCompareIcon, XIcon } from "@primer/octicons-react";

import PageContainer from "../components/layout/PageContainer";
import { AsyncBoundary, EmptyState } from "../components/common/states";
import Notice from "../components/common/Notice";
import BranchSelector from "../components/branch/BranchSelector";
import Octicon from "../components/common/Octicon";
import CommitGraph from "../components/commit/CommitGraph";
import CommitRow from "../components/commit/CommitRow";
import { graphWidth } from "../components/commit/graphMetrics";
import { useAsync } from "../hooks/useAsync";
import { useRepository } from "../hooks/useRepository";
import { commitService } from "../services/commitService";
import { buildCommitGraph } from "../utils/commitGraph";
import { basename, isFiltered, normalisePath, pathHistoryUrl } from "../utils/pathHistory";

const PAGE_SIZE = 30;

/**
 * The commit history of one revision, with its graph.
 *
 * Pages are appended rather than refetched. The engine now issues a cursor, so
 * the join between two pages is the server's own traversal rather than
 * something guessed here - which is what previously ruled appending out, since
 * the graph must never draw an edge it inferred. Every page is concatenated
 * into one list and the graph is rebuilt from that whole list, so each edge is
 * still derived from commits actually in hand.
 *
 * A path narrows the listing to the commits that touched one file or directory.
 * Whether more history remains is the server's answer, not ours: a short page
 * can mean the history ended or that the search spent its budget without
 * filling, and only one of those is the end of anything.
 */
const CommitHistory = () => {
  const { owner, name, head, canWrite, reloadHead } = useRepository();
  const params = useParams();
  const navigate = useNavigate();

  const [searchParams] = useSearchParams();

  const refName = params.ref ? decodeURIComponent(params.ref) : head?.branch ?? "HEAD";
  const path = normalisePath(searchParams.get("path"));
  const filtered = isFiltered(path);

  /* Pages already loaded, and where to continue. Reset whenever the question
     changes - a new ref or path is a different walk, and a cursor from the old
     one names a commit the server would rightly refuse. */
  const [pages, setPages] = useState([]);
  const [cursor, setCursor] = useState(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [moreError, setMoreError] = useState(null);

  const history = useAsync(
    () => commitService.historyPage(owner, name, { ref: refName, limit: PAGE_SIZE, path }),
    [owner, name, refName, path],
  );

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
        path,
        cursor,
      });
      setPages((current) => [...current, next.commits]);
      setCursor(next.hasMore ? next.nextCursor : null);
    } catch (error) {
      // The pages already shown stay shown; only the attempt to extend failed.
      setMoreError(error);
    } finally {
      setLoadingMore(false);
    }
  }, [cursor, loadingMore, owner, name, refName, path]);

  const changeRef = useCallback(
    (branch) => {
      // The path survives the branch change: someone following one file wants
      // to follow it on the other branch, not be dropped back into everything.
      navigate(pathHistoryUrl(owner, name, branch, path));
    },
    [navigate, owner, name, path],
  );

  const hasNoCommits = !head?.commit;

  return (
    <PageContainer>
      <Box
        sx={{
          display: "flex",
          alignItems: "flex-start",
          gap: 3,
          flexWrap: "wrap",
          mb: 3,
        }}
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
            Commits
          </Heading>
          <Text sx={{ fontSize: 0, color: "fg.muted" }}>
            {filtered
              ? "Only the commits that touched this path."
              : "Every line is a parent link taken from the commit itself."}
          </Text>
        </Box>

        <Button as={RouterLink} to={`/${owner}/${name}/compare`} leadingVisual={GitCompareIcon}>
          Compare
        </Button>
      </Box>

      {filtered && <PathFilter owner={owner} name={name} refName={refName} path={path} />}

      {hasNoCommits ? (
        <Panel>
          <EmptyState
            icon={GitCommitIcon}
            title="No commits yet"
            message="Nothing has been committed to this repository, so there is no history to draw."
            minHeight="220px"
          />
        </Panel>
      ) : (
        <AsyncBoundary
          loading={history.loading && commits.length === 0}
          error={history.error}
          onRetry={history.reload}
          loadingLabel="Loading history"
          minHeight="260px"
        >
          {graph.boundaries.length > 0 && (
            <Box sx={{ mb: 3 }}>
              <Notice variant="info">
                Some commits have parents outside this window. Those links end in a faded stub
                rather than a commit.
              </Notice>
            </Box>
          )}

          {filtered && commits.length === 0 && !history.loading ? (
            <Panel>
              <EmptyState
                icon={FileIcon}
                title={reachedEnd ? "Nothing changed this path" : "Nothing here yet"}
                message={
                  reachedEnd
                    ? `No commit reachable from ${refName} touched it. A file that was renamed carries its earlier life under its previous name, which is the one case where history genuinely stops short.`
                    : `Nothing in the history searched so far touched it, and there is more to search. Load more history to keep looking back from ${refName}.`
                }
                minHeight="220px"
              />
            </Panel>
          ) : (
          <Panel>
            {/* Graph and rows scroll together as one strip. Two independently
                scrolling columns would let the dots slide away from the commits
                they belong to the moment either one moved. */}
            <Box sx={{ overflowX: "auto" }}>
              {/* The strip itself may shrink to the panel; it is the graph's
                  fixed width and the rows' minimum that push past it and turn
                  the scrollbar on, only when they genuinely do not fit. Asking
                  for min-content here made every row overflow on a phone even
                  when there was room. */}
              <Box sx={{ display: "flex", minWidth: 0, alignItems: "flex-start" }}>
                <Box
                  sx={{
                    flexShrink: 0,
                    pt: 0,
                    width: `${graphWidth(graph.laneCount)}px`,
                    minWidth: `${graphWidth(graph.laneCount)}px`,
                  }}
                >
                  <CommitGraph graph={graph} />
                </Box>

                {/* Narrow enough that a two-lane graph and a row still fit a phone
                    without scrolling; wider graphs scroll, taking the rows with
                    them. */}
                <Box sx={{ flex: 1, minWidth: ["300px", "420px"] }}>
                  {graph.rows.map((node) => (
                    <CommitRow
                      key={node.sha}
                      node={node}
                      onSelect={(commit) => navigate(`/${owner}/${name}/commit/${commit.sha}`)}
                    />
                  ))}
                </Box>
              </Box>
            </Box>
          </Panel>
          )}

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
              Showing {commits.length} {commits.length === 1 ? "commit" : "commits"}
              {reachedEnd &&
                (filtered
                  ? " — everything that touched this path"
                  : " — the whole history from here")}
            </Text>

            {moreError && (
              <Notice variant="danger">
                Could not load more history. The commits above are unaffected.
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
 * Which path the listing is narrowed to, and the way back out.
 *
 * Shown only while a filter is on, so the unfiltered page is exactly what it
 * was. The full path is the title rather than the label: a deep path pushes the
 * clear control off a narrow screen, and the last segment is what identifies
 * the file to someone who just clicked through from it.
 */
const PathFilter = ({ owner, name, refName, path }) => (
  <Box
    sx={{
      display: "flex",
      alignItems: "center",
      gap: 2,
      flexWrap: "wrap",
      mb: 3,
      px: 3,
      py: 2,
      bg: "canvas.subtle",
      border: "1px solid",
      borderColor: "border.default",
      borderRadius: 2,
    }}
  >
    <Octicon icon={FileIcon} sx={{ color: "fg.muted", flexShrink: 0 }} />
    <Text sx={{ fontSize: 1, color: "fg.muted" }}>History of</Text>
    <Text
      sx={{ fontFamily: "mono", fontSize: 1, color: "fg.default", overflowWrap: "anywhere" }}
      title={path}
    >
      {basename(path)}
    </Text>

    <Link
      as={RouterLink}
      to={pathHistoryUrl(owner, name, refName, "")}
      sx={{
        display: "inline-flex",
        alignItems: "center",
        gap: 1,
        fontSize: 0,
        color: "fg.muted",
        ml: "auto",
        "&:hover": { color: "accent.fg" },
      }}
    >
      <Octicon icon={XIcon} size={12} />
      Clear
    </Link>
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

export default CommitHistory;
