import { useMemo, useState } from "react";
import RouterLink from "../components/common/RouterLink";
import { Box, Heading, Text, TextInput, Button, Link } from "@primer/react";
import { RepoIcon, SearchIcon, PlusIcon } from "@primer/octicons-react";

import PageContainer from "../components/layout/PageContainer";
import RepositoryList from "../components/repository/RepositoryList";
import { AsyncBoundary, EmptyState } from "../components/common/states";
import { repositoryCountLabel } from "../utils/repositories";
import { useAsync } from "../hooks/useAsync";
import { useAuth } from "../hooks/useAuth";
import { repoService } from "../services/repoService";

/**
 * Landing page for a signed-in user: their repositories, and a little discovery.
 *
 * Filtering happens on the already-loaded list rather than through the API. The
 * number of repositories one person owns is small, so a request per keystroke
 * would add latency without improving the result.
 */
const Dashboard = () => {
  const { currentUser } = useAuth();
  const [query, setQuery] = useState("");

  const mine = useAsync(
    () => repoService.listByOwner(currentUser.username),
    [currentUser.username],
  );
  const discover = useAsync(() => repoService.listPublic({ size: 8 }), []);

  const filtered = useMemo(() => {
    const repos = mine.data ?? [];
    const term = query.trim().toLowerCase();
    if (!term) return repos;

    return repos.filter(
      (repo) =>
        repo.name.toLowerCase().includes(term) ||
        (repo.description ?? "").toLowerCase().includes(term),
    );
  }, [mine.data, query]);

  return (
    <PageContainer>
      <Box
        sx={{
          display: "grid",
          gap: [4, 4, 5],
          gridTemplateColumns: ["1fr", "1fr", "minmax(0, 1fr) 300px"],
          alignItems: "start",
        }}
      >
        <Box sx={{ minWidth: 0 }}>
          <Box
            sx={{
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              gap: 3,
              mb: 3,
              flexWrap: "wrap",
            }}
          >
            <Heading as="h1" sx={{ fontSize: 3, fontWeight: 600 }}>
              Your repositories
            </Heading>
            <Button as={RouterLink} to="/new" variant="primary" size="small" leadingVisual={PlusIcon}>
              New
            </Button>
          </Box>

          {(mine.data?.length ?? 0) > 0 && (
            <Box sx={{ mb: 3 }}>
              <TextInput
                leadingVisual={SearchIcon}
                placeholder="Find a repository"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                aria-label="Find a repository"
                block
              />
            </Box>
          )}

          <AsyncBoundary
            loading={mine.loading}
            error={mine.error}
            onRetry={mine.reload}
            loadingLabel="Loading your repositories"
            isEmpty={(mine.data ?? []).length === 0}
            empty={
              <Panel>
                <EmptyState
                  icon={RepoIcon}
                  title="No repositories yet"
                  message="Create one to start tracking work with GitForge."
                  action={
                    <Button as={RouterLink} to="/new" variant="primary" size="small" leadingVisual={PlusIcon}>
                      New repository
                    </Button>
                  }
                />
              </Panel>
            }
          >
            {filtered.length === 0 ? (
              <Panel>
                <EmptyState
                  icon={SearchIcon}
                  title="No matches"
                  message={`Nothing matched “${query.trim()}”.`}
                  minHeight="160px"
                />
              </Panel>
            ) : (
              <RepositoryList
                repositories={filtered}
                title={repositoryCountLabel(mine.data?.length)}
                viewAll={{ to: `/${currentUser.username}`, label: "View all" }}
                onCreateHref="/new"
              />
            )}
          </AsyncBoundary>
        </Box>

        <Box sx={{ minWidth: 0 }}>
          <Heading as="h2" sx={{ fontSize: 1, fontWeight: 600, color: "fg.muted", mb: 3 }}>
            Explore
          </Heading>

          <AsyncBoundary
            loading={discover.loading}
            error={discover.error}
            onRetry={discover.reload}
            loadingLabel="Loading public repositories"
            minHeight="120px"
            isEmpty={(discover.data?.content ?? []).length === 0}
            empty={
              <Panel>
                <EmptyState
                  icon={RepoIcon}
                  title="Nothing public yet"
                  message="Public repositories will appear here."
                  minHeight="120px"
                />
              </Panel>
            }
          >
            <Box sx={{ display: "grid", gap: 2 }}>
              {(discover.data?.content ?? []).map((repo) => (
                <Box
                  key={repo.id}
                  sx={{
                    bg: "canvas.subtle",
                    border: "1px solid",
                    borderColor: "border.default",
                    borderRadius: 2,
                    p: 3,
                  }}
                >
                  <Link
                    as={RouterLink}
                    to={`/${repo.ownerUsername}/${repo.name}`}
                    sx={{ fontSize: 1, fontWeight: 600, display: "block", mb: 1 }}
                  >
                    {repo.ownerUsername}/{repo.name}
                  </Link>
                  {repo.description && (
                    <Text
                      sx={{
                        fontSize: 0,
                        color: "fg.muted",
                        display: "-webkit-box",
                        WebkitLineClamp: 2,
                        WebkitBoxOrient: "vertical",
                        overflow: "hidden",
                      }}
                    >
                      {repo.description}
                    </Text>
                  )}
                </Box>
              ))}
            </Box>
          </AsyncBoundary>
        </Box>
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
    }}
  >
    {children}
  </Box>
);

export default Dashboard;
