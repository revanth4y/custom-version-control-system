import { useCallback, useState } from "react";
import { Link as RouterLink, useNavigate, useParams } from "react-router-dom";
import { Box, Button, Heading, Link, Text, Octicon } from "@primer/react";
import { ArrowLeftIcon, CheckIcon, IssueOpenedIcon, PencilIcon, TrashIcon } from "@primer/octicons-react";

import PageContainer from "../components/layout/PageContainer";
import { AsyncBoundary } from "../components/common/states";
import Markdown from "../components/common/Markdown";
import ModalDialog from "../components/common/ModalDialog";
import Notice from "../components/common/Notice";
import IdentityAvatar from "../components/common/IdentityAvatar";
import IssueStatusBadge from "../components/issues/IssueStatusBadge";
import IssueForm from "../components/issues/IssueForm";
import CommentList from "../components/issues/CommentList";
import CommentForm from "../components/issues/CommentForm";
import { useAsync } from "../hooks/useAsync";
import { useAuth } from "../hooks/useAuth";
import { useMutation } from "../hooks/useMutation";
import { useRepository } from "../hooks/useRepository";
import { issueService } from "../services/issueService";
import {
  IssueStatus,
  authorLabel,
  canEditComment,
  canEditIssue,
  canParticipate,
  wasEdited,
} from "../utils/issues";
import { formatAbsoluteTime, formatRelativeTime } from "../utils/dates";

/**
 * One issue and its discussion.
 *
 * Every write refetches rather than patching what is on screen. The server
 * serialises a PATCH response before the transaction flushes, so the reply
 * carries the timestamp the record had *before* the edit - trusting it would
 * make an edited comment claim it had never been touched.
 */
const IssueDetailPage = () => {
  const { owner, name, repository } = useRepository();
  const { currentUser } = useAuth();
  const { number } = useParams();
  const navigate = useNavigate();

  const issue = useAsync(() => issueService.get(owner, name, number), [owner, name, number]);
  const comments = useAsync(() => issueService.listComments(owner, name, number), [owner, name, number]);

  const issueWrite = useMutation();
  const commentWrite = useMutation();

  const [editing, setEditing] = useState(false);
  const [confirmingDelete, setConfirmingDelete] = useState(null);
  const [pendingCommentId, setPendingCommentId] = useState(null);

  const data = issue.data;
  const mayEdit = canEditIssue(data, currentUser, repository);

  const saveIssue = async ({ title, body }) => {
    const result = await issueWrite.run(
      () => issueService.update(data.id, { title, body }),
      "The issue could not be saved.",
    );
    if (result.ok) {
      setEditing(false);
      issue.reload();
    }
  };

  const toggleStatus = async () => {
    const next = data.status === IssueStatus.OPEN ? IssueStatus.CLOSED : IssueStatus.OPEN;
    const result = await issueWrite.run(
      () => issueService.update(data.id, { status: next }),
      "The issue could not be updated.",
    );
    if (result.ok) issue.reload();
  };

  const deleteIssue = async () => {
    const result = await issueWrite.run(
      () => issueService.remove(data.id),
      "The issue could not be deleted.",
    );
    if (result.ok) navigate(`/${owner}/${name}/issues`);
  };

  const addComment = useCallback(
    async (body) => {
      const result = await commentWrite.run(
        () => issueService.addComment(owner, name, number, body),
        "The comment could not be posted.",
      );
      if (result.ok) comments.reload();
      return result.ok;
    },
    [commentWrite, comments, owner, name, number],
  );

  const saveComment = useCallback(
    async (comment, body) => {
      setPendingCommentId(comment.id);
      const result = await commentWrite.run(
        () => issueService.updateComment(comment.id, body),
        "The comment could not be saved.",
      );
      setPendingCommentId(null);
      if (result.ok) comments.reload();
      return result.ok;
    },
    [commentWrite, comments],
  );

  const deleteComment = async (comment) => {
    const result = await commentWrite.run(
      () => issueService.removeComment(comment.id),
      "The comment could not be deleted.",
    );
    if (result.ok) comments.reload();
    setConfirmingDelete(null);
  };

  return (
    <PageContainer>
      <Box sx={{ mb: 3 }}>
        <Link as={RouterLink} to={`/${owner}/${name}/issues`} sx={{ fontSize: 0 }}>
          <Octicon icon={ArrowLeftIcon} size={12} sx={{ mr: 1 }} />
          Issues
        </Link>
      </Box>

      <AsyncBoundary
        loading={issue.loading}
        error={issue.error}
        onRetry={issue.reload}
        loadingLabel="Loading issue"
        minHeight="220px"
      >
        {data && (
          <>
            {issueWrite.error && (
              <Box sx={{ mb: 3 }}>
                <Notice variant="danger">{issueWrite.error}</Notice>
              </Box>
            )}

            {editing ? (
              <Box
                sx={{
                  border: "1px solid",
                  borderColor: "border.default",
                  borderRadius: 2,
                  bg: "canvas.subtle",
                  p: [3, 4],
                  mb: 4,
                }}
              >
                <IssueForm
                  initialTitle={data.title}
                  initialBody={data.body ?? ""}
                  submitLabel="Save changes"
                  pendingLabel="Saving..."
                  pending={issueWrite.pending}
                  onSubmit={saveIssue}
                  onCancel={() => setEditing(false)}
                />
              </Box>
            ) : (
              <IssueHeader
                issue={data}
                mayEdit={mayEdit}
                pending={issueWrite.pending}
                onEdit={() => setEditing(true)}
                onToggleStatus={toggleStatus}
                onDelete={() => setConfirmingDelete({ kind: "issue" })}
              />
            )}

            <Box sx={{ mt: 4 }}>
              <Heading as="h3" sx={{ fontSize: 1, fontWeight: 600, mb: 3 }}>
                Discussion
              </Heading>

              <AsyncBoundary
                loading={comments.loading}
                error={comments.error}
                onRetry={comments.reload}
                loadingLabel="Loading comments"
                minHeight="120px"
              >
                <CommentList
                  comments={comments.data ?? []}
                  viewer={currentUser}
                  repository={repository}
                  canEditComment={canEditComment}
                  onSave={saveComment}
                  onDelete={(comment) => setConfirmingDelete({ kind: "comment", comment })}
                  pendingId={pendingCommentId}
                />
              </AsyncBoundary>

              <Box sx={{ mt: 4 }}>
                <CommentForm
                  canComment={canParticipate(currentUser)}
                  pending={commentWrite.pending}
                  error={commentWrite.error}
                  onSubmit={addComment}
                />
              </Box>
            </Box>
          </>
        )}
      </AsyncBoundary>

      {confirmingDelete?.kind === "issue" && (
        <ConfirmDelete
          title="Delete this issue?"
          detail={`Issue #${data.number} and its whole discussion will be removed. This cannot be undone.`}
          pending={issueWrite.pending}
          onCancel={() => setConfirmingDelete(null)}
          onConfirm={deleteIssue}
        />
      )}

      {confirmingDelete?.kind === "comment" && (
        <ConfirmDelete
          title="Delete this comment?"
          detail="The comment will be removed from the discussion. This cannot be undone."
          pending={commentWrite.pending}
          onCancel={() => setConfirmingDelete(null)}
          onConfirm={() => deleteComment(confirmingDelete.comment)}
        />
      )}
    </PageContainer>
  );
};

const IssueHeader = ({ issue, mayEdit, pending, onEdit, onToggleStatus, onDelete }) => {
  const open = issue.status === IssueStatus.OPEN;

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
        <Heading
          as="h2"
          sx={{ fontSize: 4, fontWeight: 600, lineHeight: 1.25, overflowWrap: "anywhere", mb: 2 }}
        >
          {issue.title}{" "}
          <Text as="span" sx={{ color: "fg.subtle", fontWeight: 400 }}>
            #{issue.number}
          </Text>
        </Heading>

        <Box sx={{ display: "flex", alignItems: "center", gap: 2, flexWrap: "wrap" }}>
          <IssueStatusBadge status={issue.status} />
          <IdentityAvatar username={issue.authorUsername ?? "deleted"} size={20} />
          <Text sx={{ fontSize: 0, color: issue.authorUsername ? "fg.muted" : "fg.subtle" }}>
            {authorLabel(issue.authorUsername)}
          </Text>
          <Text sx={{ fontSize: 0, color: "fg.muted" }} title={formatAbsoluteTime(issue.createdAt)}>
            opened {formatRelativeTime(issue.createdAt)}
          </Text>
          {/* "updated", not "edited": one timestamp covers the title, the body
              and the status, so closing an issue moves it too. Saying "edited"
              would claim the text changed when only the state did. A comment
              has no such ambiguity - its body is the only thing that can
              change - so it says "edited" there. */}
          {wasEdited(issue) && (
            <Text sx={{ fontSize: 0, color: "fg.subtle" }} title={formatAbsoluteTime(issue.updatedAt)}>
              · updated {formatRelativeTime(issue.updatedAt)}
            </Text>
          )}
        </Box>
      </Box>

      <Box
        sx={{
          px: [3, 4],
          py: 3,
          borderTop: "1px solid",
          borderColor: "border.muted",
        }}
      >
        {issue.body ? (
          <Markdown>{issue.body}</Markdown>
        ) : (
          <Text sx={{ fontSize: 0, color: "fg.subtle", fontStyle: "italic" }}>
            No description was given.
          </Text>
        )}
      </Box>

      {mayEdit && (
        <Box
          sx={{
            display: "flex",
            gap: 2,
            flexWrap: "wrap",
            px: [3, 4],
            py: 3,
            borderTop: "1px solid",
            borderColor: "border.muted",
            bg: "canvas.overlay",
          }}
        >
          <Button
            leadingVisual={open ? CheckIcon : IssueOpenedIcon}
            onClick={onToggleStatus}
            disabled={pending}
          >
            {open ? "Close issue" : "Reopen issue"}
          </Button>
          <Button leadingVisual={PencilIcon} onClick={onEdit} disabled={pending}>
            Edit
          </Button>
          <Button variant="danger" leadingVisual={TrashIcon} onClick={onDelete} disabled={pending}>
            Delete
          </Button>
        </Box>
      )}
    </Box>
  );
};

/** Focus starts on Cancel, so a stray Enter dismisses rather than destroys. */
const ConfirmDelete = ({ title, detail, pending, onCancel, onConfirm }) => (
  <ModalDialog
    title={title}
    role="alertdialog"
    onClose={onCancel}
    actions={
      <>
        <Button onClick={onCancel} disabled={pending}>
          Cancel
        </Button>
        <Button variant="danger" onClick={onConfirm} disabled={pending}>
          {pending ? "Deleting..." : "Delete"}
        </Button>
      </>
    }
  >
    <Notice variant="warning">{detail}</Notice>
  </ModalDialog>
);

export default IssueDetailPage;
