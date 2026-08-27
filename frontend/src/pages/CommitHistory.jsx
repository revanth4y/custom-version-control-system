import { useCallback, useMemo, useState } from "react";
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

/** The server refuses more than this, so asking for more would silently return less. */
const MAX_HISTORY = 200;

/**
 * The commit history of one revision, with its graph.
 *
 * The window grows by refetching with a larger limit rather than by appending
 * pages. The engine has no cursor, and stitching separately-fetched pages
 * together would mean guessing how they join - exactly the kind of inferred
 * relationship the graph must never draw. Refetching keeps every edge derived
 * from one coherent payload, and the layout is deterministic, so the part of
 * the graph already on screen redraws identically.
 *
 * A path narrows the listing to the commits that touched one file or directory.
 * That changes what an exhausted listing means, and the difference is worth
 * being exact about: unfiltered, running out means the history ended; filtered,
 * it means nothing else in the searched window touched this path. The second is
 * a statement about the window, not about the file, and the footer says so.
 */
const CommitHistory = () => {
  const { owner, name, head, canWrite, reloadHead } = useRepository();
  const params = useParams();
  const navigate = useNavigate();

  const [searchParams] = useSearchParams();
  const [limit, setLimit] = useState(PAGE_SIZE);

  const refName = params.ref ? decodeURIComponent(params.ref) : head?.branch ?? "HEAD";
  const path = normalisePath(searchParams.get("path"));
  const filtered = isFiltered(path);

  const history = useAsync(
    () => commitService.history(owner, name, { ref: refName, limit, path }),
    [owner, name, refName, limit, path],
  );

  const commits = useMemo(() => history.data ?? [], [history.data]);
  const graph = useMemo(() => buildCommitGraph(commits), [commits]);

  /* The server returning fewer commits than asked for is how we know there is
     no more to load; there is no flag for it. What that exhaustion *means*
     differs: unfiltered it is the end of the history, filtered it is the end of
     the window the server searched. */
  const reachedEnd = commits.length < limit;
  const atServerCap = limit >= MAX_HISTORY;

  const changeRef = useCallback(
    (branch) => {
      setLimit(PAGE_SIZE);
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
                title="Nothing here changed this path"
                message={`No commit in the ${MAX_HISTORY} searched from ${refName} touched it. That is what was searched, not what exists: an older change falls outside the window, and a file that was renamed carries its earlier life under its previous name.`}
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
                  ? ` — everything touching this path in the ${MAX_HISTORY} searched`
                  : " — the whole history from here")}
              {!reachedEnd && atServerCap && " — the most this view will load"}
            </Text>

            {!reachedEnd && !atServerCap && (
              <Button
                onClick={() => setLimit((current) => Math.min(current + PAGE_SIZE, MAX_HISTORY))}
                disabled={history.loading}
                leadingVisual={history.loading ? undefined : GitCommitIcon}
              >
                {history.loading ? (
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
