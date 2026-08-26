import { useCallback, useMemo } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { Box, Heading, Text } from "@primer/react";
import Octicon from "../components/common/Octicon";
import { BookIcon, RepoIcon } from "@primer/octicons-react";

import PageContainer from "../components/layout/PageContainer";
import { AsyncBoundary, EmptyState, LoadingState } from "../components/common/states";
import { headResolved, isEmptyRepository, shouldLoadTree } from "../utils/repositoryState";
import Markdown from "../components/common/Markdown";
import BranchSelector from "../components/branch/BranchSelector";
import FileTree from "../components/repository/FileTree";
import LatestCommitBar from "../components/repository/LatestCommitBar";
import PathBreadcrumb from "../components/repository/PathBreadcrumb";
import RepositoryMeta from "../components/repository/RepositoryMeta";
import { useAsync } from "../hooks/useAsync";
import { useRepository } from "../hooks/useRepository";
import { commitService } from "../services/commitService";
import { contentService } from "../services/contentService";
import { insightsService } from "../services/insightsService";

/** Filenames treated as the repository's README, in order of preference. */
const README_NAMES = ["README.md", "readme.md", "README", "readme", "README.markdown"];

/**
 * Browsing a repository's files at a revision.
 *
 * The revision comes from the URL when present so a link to a branch and path is
 * shareable and survives a reload; otherwise it falls back to whatever HEAD
 * names.
 */
const RepositoryCode = () => {
  const { owner, name, head, canWrite, reloadHead, repository } = useRepository();
  const params = useParams();
  const navigate = useNavigate();

  const refName = params.ref ? decodeURIComponent(params.ref) : head?.branch ?? "HEAD";
  const path = params["*"] ?? "";

  /* The listing carries the commit that last touched each entry. It costs no
     extra request — the server walks history once for the whole directory.

     Not requested until HEAD has resolved and names a commit. A repository with
     no commits has no tree, and the server says so with a 404 — correctly, but
     HEAD has already told us, so the request only produces an error in the
     console for something we knew. */
  const loadTree = shouldLoadTree(head);
  const tree = useAsync(
    () =>
      loadTree
        ? contentService.tree(owner, name, { ref: refName, path, withLastCommit: true })
        : Promise.resolve(null),
    [owner, name, refName, path, loadTree],
  );

  /* The metadata rail describes the repository as a whole, so it is fetched at
     the root and not again on the way down a directory. */
  const insights = useAsync(
    () => (path ? Promise.resolve(null) : insightsService.forRepository(owner, name)),
    [owner, name, path],
  );

  /* The latest commit belongs to the revision, not to the directory being
     viewed, so `path` is deliberately not a dependency: descending into a
     folder re-reads the listing without re-reading this. */
  const latest = useAsync(
    () => commitService.history(owner, name, { ref: refName, limit: 1 }),
    [owner, name, refName],
  );

  /* Memoised so the identity is stable between renders; the README lookup
     below depends on it and would otherwise re-run on every pass. */
  const entries = useMemo(() => tree.data?.entries ?? [], [tree.data]);

  // Only the repository root shows a README, matching where one is meant to live.
  const readmeEntry = useMemo(
    () => (path ? null : entries.find((entry) => entry.type === "file" && README_NAMES.includes(entry.name))),
    [entries, path],
  );

  const readme = useAsync(
    () =>
      readmeEntry
        ? contentService.blob(owner, name, { ref: refName, path: readmeEntry.path })
        : Promise.resolve(null),
    [owner, name, refName, readmeEntry?.path],
  );

  const changeRef = useCallback(
    (branch) => {
      const suffix = path ? `/${path}` : "";
      navigate(`/${owner}/${name}/tree/${encodeURIComponent(branch)}${suffix}`);
    },
    [navigate, owner, name, path],
  );

  /* Three states, not two. A repository with no commits has no tree at all, and
     the API reports that as a missing path rather than as a failure — but until
     HEAD answers, "no commit" and "not yet known" look identical, and treating
     the second as the first shows the empty state to someone whose repository
     is merely still loading. */
  const headIsKnown = headResolved(head);
  const hasNoCommits = isEmptyRepository(head);

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
          <PathBreadcrumb owner={owner} name={name} refName={refName} path={path} />
        </Box>
      </Box>

      {/* Content beside the metadata rail, as the reference lays it out. The
          rail drops beneath the listing before it would squeeze it. */}
      <Box
        sx={{
          display: "grid",
          gap: [3, 3, 4],
          gridTemplateColumns: ["1fr", "1fr", "minmax(0, 1fr) 296px"],
          alignItems: "start",
        }}
      >
        <Box sx={{ minWidth: 0 }}>
      {!headIsKnown ? (
        <LoadingState label="Loading files" minHeight="220px" />
      ) : hasNoCommits ? (
        <Panel>
          <EmptyState
            icon={RepoIcon}
            title="This repository is empty"
            message={
              canWrite
                ? "Nothing has been committed yet. The first commit creates the default branch."
                : "Nothing has been committed to this repository yet."
            }
            minHeight="220px"
          />
        </Panel>
      ) : (
        <AsyncBoundary
          loading={tree.loading}
          error={tree.error}
          onRetry={tree.reload}
          loadingLabel="Loading files"
          minHeight="220px"
        >
          <Panel>
            {/* The endpoint answers with a bare array of commits. */}
            <LatestCommitBar owner={owner} name={name} commit={latest.data?.[0]} />
            <FileTree owner={owner} name={name} refName={refName} entries={entries} />
          </Panel>

          {readmeEntry && (
            <Box sx={{ mt: 4 }}>
              <Panel>
                <Box
                  sx={{
                    display: "flex",
                    alignItems: "center",
                    gap: 2,
                    px: 3,
                    py: 2,
                    borderBottom: "1px solid",
                    borderColor: "border.muted",
                  }}
                >
                  <Octicon icon={BookIcon} sx={{ color: "fg.muted" }} />
                  <Heading as="h2" sx={{ fontSize: 1, fontWeight: 600 }}>
                    {readmeEntry.name}
                  </Heading>
                </Box>

                <Box sx={{ p: [3, 4] }}>
                  <AsyncBoundary
                    loading={readme.loading}
                    error={readme.error}
                    onRetry={readme.reload}
                    loadingLabel="Loading README"
                    minHeight="120px"
                  >
                    {readme.data?.binary ? (
                      <Text sx={{ color: "fg.muted", fontSize: 1 }}>
                        This README is not text and cannot be displayed.
                      </Text>
                    ) : (
                      <Markdown>{readme.data?.content ?? ""}</Markdown>
                    )}
                  </AsyncBoundary>
                </Box>
              </Panel>
            </Box>
          )}
        </AsyncBoundary>
      )}
        </Box>

        {/* Root only: the figures describe the repository, not the folder. */}
        {!path && (
          <Box sx={{ minWidth: 0 }}>
            <RepositoryMeta insights={insights.data} repository={repository} />
          </Box>
        )}
      </Box>
    </PageContainer>
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

export default RepositoryCode;
