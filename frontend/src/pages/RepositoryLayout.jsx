import { Outlet, useParams } from "react-router-dom";
import { Box } from "@primer/react";

import { RepositoryProvider } from "../context/RepositoryProvider";
import { useRepository } from "../hooks/useRepository";
import RepoHeader from "../components/repository/RepoHeader";
import RepoNav from "../components/repository/RepoNav";
import PageContainer from "../components/layout/PageContainer";
import { ErrorState, LoadingState } from "../components/common/states";
import { repositoryKey } from "../utils/repositoryState";

/**
 * The frame every repository page sits inside.
 *
 * The provider wraps the frame rather than each page, so metadata and HEAD are
 * fetched once for the repository and survive moving between tabs.
 */
const RepositoryLayout = () => {
  const { username, repo } = useParams();

  /* Keyed by the repository being viewed, so moving to a different one remounts
     rather than re-renders.
     Without the key the provider went on serving the previous repository's
     metadata while its children re-rendered against the new owner and name.
     Three things followed, all of them observable: requests for the new
     repository were issued before its metadata had resolved, a stale README
     path was fetched from the repository just left, and a repository that does
     not exist never showed its error — the gates test `!repository`, and
     `repository` still held the previous one, so neither the loading nor the
     error branch was ever reached and the old page simply stayed on screen.
     Remounting resets that state to null, which is what those gates were
     written to expect. */
  return (
    <RepositoryProvider key={repositoryKey(username, repo)} owner={username} name={repo}>
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
