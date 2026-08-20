import { useCallback, useMemo } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { Box, Heading, Text, Octicon } from "@primer/react";
import { BookIcon, FileDirectoryFillIcon, RepoIcon } from "@primer/octicons-react";

import PageContainer from "../components/layout/PageContainer";
import { AsyncBoundary, EmptyState } from "../components/common/states";
import Markdown from "../components/common/Markdown";
import BranchSelector from "../components/branch/BranchSelector";
import FileTree from "../components/repository/FileTree";
import PathBreadcrumb from "../components/repository/PathBreadcrumb";
import { useAsync } from "../hooks/useAsync";
import { useRepository } from "../hooks/useRepository";
import { contentService } from "../services/contentService";

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
  const { owner, name, head, canWrite, reloadHead } = useRepository();
  const params = useParams();
  const navigate = useNavigate();

  const refName = params.ref ? decodeURIComponent(params.ref) : head?.branch ?? "HEAD";
  const path = params["*"] ?? "";

  const tree = useAsync(
    () => contentService.tree(owner, name, { ref: refName, path }),
    [owner, name, refName, path],
  );

  const entries = tree.data?.entries ?? [];

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

  // A repository with no commits has no tree at all; the API reports that as a
  // missing path, which is the expected state rather than a failure.
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
          <PathBreadcrumb owner={owner} name={name} refName={refName} path={path} />
        </Box>
      </Box>

      {hasNoCommits ? (
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
            <FileTree
              owner={owner}
              name={name}
              refName={refName}
              entries={entries}
              parentPath={path}
            />
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
