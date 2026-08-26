import { useParams } from "react-router-dom";
import RouterLink from "../components/common/RouterLink";
import { Box, Heading, Label, Link, Text } from "@primer/react";
import Octicon from "../components/common/Octicon";
import { GitCommitIcon, GitMergeIcon, FileDirectoryIcon } from "@primer/octicons-react";

import PageContainer from "../components/layout/PageContainer";
import { AsyncBoundary } from "../components/common/states";
import DiffViewer from "../components/diff/DiffViewer";
import IdentityAvatar from "../components/common/IdentityAvatar";
import { useAsync } from "../hooks/useAsync";
import { useRepository } from "../hooks/useRepository";
import { commitService } from "../services/commitService";
import { formatAbsoluteTime, formatRelativeTime } from "../utils/dates";
import { subjectOf } from "../utils/branches";

/**
 * One commit: what it says, who wrote it, what it descends from, what it changed.
 *
 * Metadata and the line diff come from two endpoints because they answer two
 * questions. The detail endpoint reports the commit and its file-level changes;
 * the diff endpoint reports the lines. Both are shown, and neither is derived
 * from the other.
 */
const CommitDetail = () => {
  const { owner, name } = useRepository();
  const { sha } = useParams();

  const detail = useAsync(() => commitService.detail(owner, name, sha), [owner, name, sha]);
  const diff = useAsync(() => commitService.commitDiff(owner, name, sha), [owner, name, sha]);

  return (
    <PageContainer>
      <AsyncBoundary
        loading={detail.loading}
        error={detail.error}
        onRetry={detail.reload}
        loadingLabel="Loading commit"
        minHeight="220px"
      >
        {detail.data && (
          <>
            <CommitHeader owner={owner} name={name} commit={detail.data.commit} />

            <Box sx={{ mt: 4 }}>
              <AsyncBoundary
                loading={diff.loading}
                error={diff.error}
                onRetry={diff.reload}
                loadingLabel="Loading changes"
                minHeight="180px"
              >
                {diff.data && <DiffViewer result={diff.data} />}
              </AsyncBoundary>
            </Box>
          </>
        )}
      </AsyncBoundary>
    </PageContainer>
  );
};

const CommitHeader = ({ owner, name, commit }) => {
  const message = commit.message ?? "";
  const subject = subjectOf(message);
  const body = message.split("\n").slice(1).join("\n").trim();

  return (
    <Box
      sx={{
        border: "1px solid",
        borderColor: "border.default",
        borderRadius: 2,
        bg: "canvas.subtle",
        overflow: "hidden",
      }}
    >
      <Box sx={{ px: [3, 4], py: 3 }}>
        <Box sx={{ display: "flex", alignItems: "baseline", gap: 2, flexWrap: "wrap", mb: 2 }}>
          {/* h2, not h1: the repository header above already owns the page's
              single top-level heading.

              The icon sits inside the heading rather than beside it, so a
              subject long enough to wrap cannot leave the icon stranded on a
              line of its own. */}
          <Heading
            as="h2"
            sx={{ fontSize: 3, fontWeight: 600, minWidth: 0, overflowWrap: "anywhere", lineHeight: 1.3 }}
          >
            <Octicon
              icon={commit.merge ? GitMergeIcon : GitCommitIcon}
              sx={{ color: commit.merge ? "accent.fg" : "fg.muted", mr: 2, verticalAlign: "baseline" }}
            />
            {subject}
          </Heading>
          {commit.merge && (
            <Label sx={{ color: "accent.fg", borderColor: "accent.muted", flexShrink: 0 }}>
              merge commit
            </Label>
          )}
        </Box>

        {body && (
          <Text
            as="pre"
            sx={{
              fontFamily: "mono",
              fontSize: 0,
              color: "fg.muted",
              bg: "canvas.inset",
              border: "1px solid",
              borderColor: "border.muted",
              borderRadius: 2,
              p: 3,
              mt: 0,
              mb: 3,
              whiteSpace: "pre-wrap",
              overflowWrap: "anywhere",
            }}
          >
            {body}
          </Text>
        )}

        <Box sx={{ display: "flex", alignItems: "center", gap: 2, flexWrap: "wrap" }}>
          <IdentityAvatar username={commit.authorName} size={20} />
          <Text sx={{ fontSize: 1, fontWeight: 600 }}>{commit.authorName}</Text>
          <Text sx={{ fontSize: 0, color: "fg.muted", overflowWrap: "anywhere" }}>
            {commit.authorEmail}
          </Text>
          <Text sx={{ fontSize: 0, color: "fg.muted" }} title={formatAbsoluteTime(commit.timestamp)}>
            committed {formatRelativeTime(commit.timestamp)}
          </Text>
        </Box>
      </Box>

      <Box
        sx={{
          display: "flex",
          flexDirection: ["column", "row"],
          flexWrap: "wrap",
          gap: [2, 4],
          px: [3, 4],
          py: 3,
          borderTop: "1px solid",
          borderColor: "border.muted",
          bg: "canvas.overlay",
        }}
      >
        <Field label="commit">
          <Text sx={{ fontFamily: "mono", fontSize: 0, overflowWrap: "anywhere" }} title={commit.sha}>
            {commit.sha}
          </Text>
        </Field>

        <Field label="tree">
          <Box sx={{ display: "inline-flex", alignItems: "center", gap: 1 }}>
            <Octicon icon={FileDirectoryIcon} size={12} sx={{ color: "fg.subtle" }} />
            <Link
              as={RouterLink}
              to={`/${owner}/${name}/tree/${encodeURIComponent(commit.sha)}`}
              sx={{ fontFamily: "mono", fontSize: 0 }}
              title={commit.tree}
            >
              {commit.tree?.slice(0, 12)}
            </Link>
          </Box>
        </Field>

        <Parents owner={owner} name={name} parents={commit.parents ?? []} />
      </Box>
    </Box>
  );
};

/**
 * The commits this one descends from.
 *
 * For a merge the first parent is marked as such, because the distinction is
 * load-bearing: it is the branch that was merged into, and the one this
 * commit's changes are measured against.
 */
const Parents = ({ owner, name, parents }) => {
  if (parents.length === 0) {
    return (
      <Field label="parent">
        <Text sx={{ fontSize: 0, color: "fg.muted" }}>none — this is a root commit</Text>
      </Field>
    );
  }

  return (
    <Field label={parents.length === 1 ? "parent" : `parents (${parents.length})`}>
      <Box sx={{ display: "flex", flexDirection: "column", gap: 1 }}>
        {parents.map((parent, index) => (
          <Box key={parent} sx={{ display: "flex", alignItems: "center", gap: 2 }}>
            <Link
              as={RouterLink}
              to={`/${owner}/${name}/commit/${parent}`}
              sx={{ fontFamily: "mono", fontSize: 0 }}
              title={parent}
            >
              {parent.slice(0, 12)}
            </Link>
            {parents.length > 1 && (
              <Text sx={{ fontSize: 0, color: index === 0 ? "accent.fg" : "fg.subtle" }}>
                {index === 0 ? "first parent — changes shown against this" : `parent ${index + 1}`}
              </Text>
            )}
          </Box>
        ))}
      </Box>
    </Field>
  );
};

const Field = ({ label, children }) => (
  <Box sx={{ minWidth: 0 }}>
    <Text sx={{ display: "block", fontSize: 0, color: "fg.subtle", mb: 1 }}>{label}</Text>
    {children}
  </Box>
);

export default CommitDetail;
