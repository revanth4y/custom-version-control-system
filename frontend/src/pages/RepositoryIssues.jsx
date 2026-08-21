import { useMemo } from "react";
import { Link as RouterLink, useSearchParams } from "react-router-dom";
import { Box, Button, Heading, Text } from "@primer/react";
import { IssueOpenedIcon, PlusIcon, SearchIcon } from "@primer/octicons-react";

import PageContainer from "../components/layout/PageContainer";
import { AsyncBoundary, EmptyState } from "../components/common/states";
import IssueFilters from "../components/issues/IssueFilters";
import IssueRow from "../components/issues/IssueRow";
import { useAsync } from "../hooks/useAsync";
import { useAuth } from "../hooks/useAuth";
import { useRepository } from "../hooks/useRepository";
import { issueService } from "../services/issueService";
import { StatusFilter, canParticipate, countByStatus, filterIssues, normaliseStatusFilter } from "../utils/issues";

/**
 * Every issue in the repository.
 *
 * The whole list is fetched once and filtered here rather than per request.
 * The endpoint has no pagination, so the full list arrives regardless; doing
 * the work locally gives both tallies from one request and makes switching
 * filters instant.
 *
 * The limitation that comes with that is worth stating: a repository with
 * thousands of issues would fetch all of them. That is the point at which the
 * endpoint needs paging, and no amount of client-side care substitutes for it.
 */
const RepositoryIssues = () => {
  const { owner, name } = useRepository();
  const { currentUser } = useAuth();
  const [params, setParams] = useSearchParams();

  const status = normaliseStatusFilter(params.get("status"));
  const query = params.get("q") ?? "";

  const issues = useAsync(() => issueService.list(owner, name), [owner, name]);
  const all = useMemo(() => issues.data ?? [], [issues.data]);

  const counts = useMemo(() => countByStatus(all), [all]);
  const visible = useMemo(() => filterIssues(all, { status, query }), [all, status, query]);

  const setParam = (key, value) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    setParams(next, { replace: true });
  };

  return (
    <PageContainer>
      <Box
        sx={{
          display: "flex",
          alignItems: "flex-start",
          justifyContent: "space-between",
          gap: 3,
          flexWrap: "wrap",
          mb: 3,
        }}
      >
        <Box sx={{ minWidth: 0 }}>
          <Heading as="h2" sx={{ fontSize: 3, fontWeight: 600, mb: 1 }}>
            Issues
          </Heading>
          <Text sx={{ fontSize: 1, color: "fg.muted" }}>
            Anything worth writing down about this repository.
          </Text>
        </Box>

        {/* Any signed-in reader may open an issue - this is not the owner-only
            rule that branches and merges use. */}
        {canParticipate(currentUser) && (
          <Button as={RouterLink} to={`/${owner}/${name}/issues/new`} variant="primary" leadingVisual={PlusIcon}>
            New issue
          </Button>
        )}
      </Box>

      <AsyncBoundary
        loading={issues.loading}
        error={issues.error}
        onRetry={issues.reload}
        loadingLabel="Loading issues"
        minHeight="220px"
      >
        {all.length === 0 ? (
          <Panel>
            <EmptyState
              icon={IssueOpenedIcon}
              title="No issues yet"
              message={
                canParticipate(currentUser)
                  ? "Nothing has been filed against this repository. The first issue starts the list."
                  : "Nothing has been filed against this repository."
              }
              action={
                canParticipate(currentUser) ? (
                  <Button as={RouterLink} to={`/${owner}/${name}/issues/new`} leadingVisual={PlusIcon}>
                    New issue
                  </Button>
                ) : undefined
              }
              minHeight="220px"
            />
          </Panel>
        ) : (
          <>
            <IssueFilters
              status={status}
              query={query}
              counts={counts}
              onStatusChange={(value) => setParam("status", value === StatusFilter.OPEN ? "" : value)}
              onQueryChange={(value) => setParam("q", value)}
            />

            {visible.length === 0 ? (
              <Panel>
                <EmptyState
                  icon={SearchIcon}
                  title="No issues match"
                  message="Try a different search, or another state."
                  action={
                    <Button onClick={() => setParams(new URLSearchParams(), { replace: true })}>
                      Clear filters
                    </Button>
                  }
                  minHeight="200px"
                />
              </Panel>
            ) : (
              <Panel>
                {visible.map((issue, index) => (
                  <IssueRow
                    key={issue.id}
                    owner={owner}
                    name={name}
                    issue={issue}
                    first={index === 0}
                  />
                ))}
              </Panel>
            )}

            <Text sx={{ display: "block", fontSize: 0, color: "fg.subtle", mt: 3 }}>
              Showing {visible.length} of {counts.total}. This list is not paginated, so every issue
              is loaded at once.
            </Text>
          </>
        )}
      </AsyncBoundary>
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

export default RepositoryIssues;
