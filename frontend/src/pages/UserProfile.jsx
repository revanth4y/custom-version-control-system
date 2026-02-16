import { Link as RouterLink, useParams } from "react-router-dom";
import { Box, Heading, Label, Link, Text, Octicon } from "@primer/react";
import { LockIcon, PersonIcon, RepoIcon } from "@primer/octicons-react";

import PageContainer from "../components/layout/PageContainer";
import { AsyncBoundary, EmptyState } from "../components/common/states";
import IdentityAvatar from "../components/common/IdentityAvatar";
import ContributionGraph from "../components/contributions/ContributionGraph";
import { useAsync } from "../hooks/useAsync";
import { useAuth } from "../hooks/useAuth";
import { userService } from "../services/userService";
import { repoService } from "../services/repoService";
import { formatAbsoluteTime, formatRelativeTime } from "../utils/dates";

/**
 * Someone's public profile.
 *
 * Readable without an account, like the repositories it lists. What a visitor
 * sees is decided by the server: a stranger gets only public repositories, and
 * the contribution figures are counted from those alone, so nothing private
 * leaks through the total.
 */
const UserProfile = () => {
  const { username } = useParams();
  const { currentUser } = useAuth();

  const profile = useAsync(() => userService.profile(username), [username]);
  const repositories = useAsync(() => repoService.listByOwner(username), [username]);
  const contributions = useAsync(() => userService.contributions(username), [username]);

  const isSelf = currentUser?.username === username;

  return (
    <PageContainer>
      <AsyncBoundary
        loading={profile.loading}
        error={profile.error}
        onRetry={profile.reload}
        loadingLabel="Loading profile"
        errorTitle="No such user"
        minHeight="260px"
      >
        {profile.data && (
          <Box
            sx={{
              display: "grid",
              gridTemplateColumns: ["1fr", "1fr", "minmax(0, 260px) minmax(0, 1fr)"],
              gap: 4,
              alignItems: "start",
            }}
          >
            <ProfileHeader
              profile={profile.data}
              repositoryCount={repositories.data?.length}
              isSelf={isSelf}
            />

            <Box sx={{ minWidth: 0, display: "flex", flexDirection: "column", gap: 4 }}>
              <Section title="Contributions">
                <AsyncBoundary
                  loading={contributions.loading}
                  error={contributions.error}
                  onRetry={contributions.reload}
                  loadingLabel="Loading contributions"
                  minHeight="140px"
                >
                  {contributions.data && <ContributionGraph contributions={contributions.data} />}
                </AsyncBoundary>
              </Section>

              <Section title="Repositories">
                <AsyncBoundary
                  loading={repositories.loading}
                  error={repositories.error}
                  onRetry={repositories.reload}
                  loadingLabel="Loading repositories"
                  minHeight="140px"
                >
                  <Repositories
                    repositories={repositories.data ?? []}
                    username={username}
                    isSelf={isSelf}
                  />
                </AsyncBoundary>
              </Section>
            </Box>
          </Box>
        )}
      </AsyncBoundary>
    </PageContainer>
  );
};

const ProfileHeader = ({ profile, repositoryCount, isSelf }) => (
  <Box sx={{ minWidth: 0 }}>
    <Box sx={{ mb: 3 }}>
      <IdentityAvatar username={profile.username} size={96} />
    </Box>

    <Heading as="h1" sx={{ fontSize: 4, fontWeight: 600, overflowWrap: "anywhere", lineHeight: 1.2 }}>
      {profile.displayName || profile.username}
    </Heading>

    {/* The display name is optional, so the username is shown beneath it only
        when the two differ - otherwise the same word appears twice. */}
    {profile.displayName && (
      <Text sx={{ display: "block", fontSize: 2, color: "fg.muted", overflowWrap: "anywhere" }}>
        {profile.username}
      </Text>
    )}

    {profile.bio ? (
      <Text sx={{ display: "block", fontSize: 1, color: "fg.default", mt: 2, overflowWrap: "anywhere" }}>
        {profile.bio}
      </Text>
    ) : (
      <Text sx={{ display: "block", fontSize: 0, color: "fg.subtle", mt: 2, fontStyle: "italic" }}>
        {isSelf ? "You have not written a bio yet." : "No bio."}
      </Text>
    )}

    <Box sx={{ display: "flex", flexDirection: "column", gap: 1, mt: 3 }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 2 }}>
        <Octicon icon={RepoIcon} size={14} sx={{ color: "fg.subtle" }} />
        <Text sx={{ fontSize: 0, color: "fg.muted" }}>
          {repositoryCount === undefined
            ? "counting repositories"
            : `${repositoryCount} ${repositoryCount === 1 ? "repository" : "repositories"}`}
        </Text>
      </Box>
      <Box sx={{ display: "flex", alignItems: "center", gap: 2 }}>
        <Octicon icon={PersonIcon} size={14} sx={{ color: "fg.subtle" }} />
        <Text sx={{ fontSize: 0, color: "fg.muted" }} title={formatAbsoluteTime(profile.createdAt)}>
          joined {formatRelativeTime(profile.createdAt)}
        </Text>
      </Box>
    </Box>
  </Box>
);

const Repositories = ({ repositories, username, isSelf }) => {
  if (repositories.length === 0) {
    return (
      <EmptyState
        icon={RepoIcon}
        title="No repositories"
        message={
          isSelf
            ? "You have not created a repository yet."
            : `${username} has no public repositories.`
        }
        minHeight="160px"
      />
    );
  }

  return (
    <Box sx={{ display: "flex", flexDirection: "column" }}>
      {repositories.map((repo, index) => (
        <Box
          key={repo.id}
          sx={{
            display: "flex",
            alignItems: "flex-start",
            gap: 2,
            py: 3,
            minWidth: 0,
            borderTop: index === 0 ? "none" : "1px solid",
            borderColor: "border.muted",
          }}
        >
          <Octicon
            icon={repo.visibility === "PRIVATE" ? LockIcon : RepoIcon}
            sx={{ color: "fg.muted", mt: "2px", flexShrink: 0 }}
          />
          <Box sx={{ minWidth: 0, flex: 1 }}>
            <Box sx={{ display: "flex", alignItems: "center", gap: 2, flexWrap: "wrap" }}>
              <Link
                as={RouterLink}
                to={`/${username}/${repo.name}`}
                sx={{ fontSize: 1, fontWeight: 600, overflowWrap: "anywhere" }}
              >
                {repo.name}
              </Link>
              {repo.visibility === "PRIVATE" && (
                <Label sx={{ color: "fg.muted", borderColor: "border.default", flexShrink: 0 }}>
                  Private
                </Label>
              )}
            </Box>
            {repo.description && (
              <Text sx={{ display: "block", fontSize: 0, color: "fg.muted", mt: 1, maxWidth: "70ch" }}>
                {repo.description}
              </Text>
            )}
            <Text sx={{ display: "block", fontSize: 0, color: "fg.subtle", mt: 1 }}>
              updated {formatRelativeTime(repo.updatedAt)}
            </Text>
          </Box>
        </Box>
      ))}
    </Box>
  );
};

const Section = ({ title, children }) => (
  <Box sx={{ minWidth: 0 }}>
    <Heading as="h2" sx={{ fontSize: 2, fontWeight: 600, mb: 3 }}>
      {title}
    </Heading>
    <Box
      sx={{
        border: "1px solid",
        borderColor: "border.default",
        borderRadius: 2,
        bg: "canvas.subtle",
        px: 3,
        py: 3,
        minWidth: 0,
      }}
    >
      {children}
    </Box>
  </Box>
);

export default UserProfile;
