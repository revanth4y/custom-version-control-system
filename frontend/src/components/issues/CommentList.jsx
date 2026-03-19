import { Box, Text } from "@primer/react";

import Comment from "./Comment";

/**
 * The thread, oldest first, as the server orders it.
 *
 * An issue with no comments is not an error state - it is the normal beginning
 * of a discussion, so it says so quietly rather than filling the page.
 */
const CommentList = ({ comments, viewer, repository, canEditComment, onSave, onDelete, pendingId }) => {
  if (comments.length === 0) {
    return (
      <Text sx={{ fontSize: 0, color: "fg.subtle", display: "block" }}>
        No comments yet.
      </Text>
    );
  }

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 3 }}>
      {comments.map((comment) => (
        <Comment
          key={comment.id}
          comment={comment}
          canEdit={canEditComment(comment, viewer, repository)}
          onSave={onSave}
          onDelete={onDelete}
          pending={pendingId === comment.id}
        />
      ))}
    </Box>
  );
};

export default CommentList;
