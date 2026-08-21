import { useState } from "react";
import { Box, Button, Text, Textarea } from "@primer/react";

import Markdown from "../common/Markdown";
import IdentityAvatar from "../common/IdentityAvatar";
import CommentActions from "./CommentActions";
import { authorLabel, wasEdited } from "../../utils/issues";
import { formatAbsoluteTime, formatRelativeTime } from "../../utils/dates";
import { MAX_BODY } from "./IssueForm";

/**
 * One comment in the thread.
 *
 * The author may be null: the schema clears it when an account is deleted
 * rather than cascading, so a conversation outlives the people who left it.
 * That is said plainly instead of showing an empty name.
 */
const Comment = ({ comment, canEdit, onSave, onDelete, pending }) => {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(comment.body);

  const orphaned = !comment.authorUsername;

  const save = async () => {
    const saved = await onSave(comment, draft);
    if (saved) setEditing(false);
  };

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
      <Box
        sx={{
          display: "flex",
          alignItems: "center",
          gap: 2,
          flexWrap: "wrap",
          px: 3,
          py: 2,
          bg: "canvas.overlay",
          borderBottom: "1px solid",
          borderColor: "border.muted",
        }}
      >
        <IdentityAvatar username={comment.authorUsername ?? "deleted"} size={20} />
        <Text sx={{ fontSize: 1, fontWeight: 600, color: orphaned ? "fg.subtle" : "fg.default" }}>
          {authorLabel(comment.authorUsername)}
        </Text>
        <Text sx={{ fontSize: 0, color: "fg.muted" }} title={formatAbsoluteTime(comment.createdAt)}>
          commented {formatRelativeTime(comment.createdAt)}
        </Text>
        {wasEdited(comment) && (
          <Text sx={{ fontSize: 0, color: "fg.subtle" }} title={formatAbsoluteTime(comment.updatedAt)}>
            · edited
          </Text>
        )}

        {canEdit && !editing && (
          <Box sx={{ ml: "auto", flexShrink: 0 }}>
            <CommentActions onEdit={() => { setDraft(comment.body); setEditing(true); }} onDelete={() => onDelete(comment)} />
          </Box>
        )}
      </Box>

      <Box sx={{ px: 3, py: 3 }}>
        {editing ? (
          <Box sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
            <Textarea
              block
              rows={6}
              value={draft}
              maxLength={MAX_BODY}
              aria-label="Edit comment"
              onChange={(event) => setDraft(event.target.value)}
              sx={{ fontFamily: "mono", fontSize: 0 }}
            />
            <Box sx={{ display: "flex", gap: 2, flexWrap: "wrap" }}>
              <Button variant="primary" onClick={save} disabled={pending || !draft.trim()}>
                {pending ? "Saving..." : "Save"}
              </Button>
              <Button onClick={() => setEditing(false)} disabled={pending}>
                Cancel
              </Button>
            </Box>
          </Box>
        ) : (
          <Markdown>{comment.body}</Markdown>
        )}
      </Box>
    </Box>
  );
};

export default Comment;
