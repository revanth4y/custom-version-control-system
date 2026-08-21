import { Outlet, useParams } from "react-router-dom";
import { Box } from "@primer/react";

import { RepositoryProvider } from "../context/RepositoryProvider";
import { useRepository } from "../hooks/useRepository";
import RepoHeader from "../components/repository/RepoHeader";
import RepoNav from "../components/repository/RepoNav";
import PageContainer from "../components/layout/PageContainer";
import { ErrorState, LoadingState } from "../components/common/states";

/**
 * The frame every repository page sits inside.
 *
 * The provider wraps the frame rather than each page, so metadata and HEAD are
 * fetched once for the repository and survive moving between tabs.
 */
const RepositoryLayout = () => {
  const { username, repo } = useParams();

  return (
    <RepositoryProvider owner={username} name={repo}>
      <RepositoryFrame />
    </RepositoryProvider>
  );
};

const RepositoryFrame = () => {
  const { owner, name, repository, loading, error, reload } = useRepository();

  if (loading && !repository) {
    return <LoadingState label="Loading repository" minHeight="60vh" />;
  }

  if (error && !repository) {
    return (
      <PageContainer>
        <ErrorState
          title="Repository unavailable"
          // A private repository reports itself as missing, so the wording has
          // to cover both cases without implying which one applies.
          message={error}
          onRetry={reload}
          minHeight="40vh"
        />
      </PageContainer>
    );
  }

  return (
    <Box>
      <RepoHeader repository={repository} />
      <RepoNav owner={owner} name={name} />
      <Outlet />
    </Box>
  );
};

export default RepositoryLayout;
