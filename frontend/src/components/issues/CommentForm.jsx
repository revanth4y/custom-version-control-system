import { useState } from "react";
import { Box, Button, Text, Textarea } from "@primer/react";

import Notice from "../common/Notice";
import { MAX_BODY } from "./IssueForm";

/**
 * Adding a comment.
 *
 * Offered to any signed-in reader, not only the repository owner: a discussion
 * that only its owner may join is not a discussion. Anonymous visitors are told
 * what they would need to do rather than shown a control that would be refused.
 */
const CommentForm = ({ canComment, pending, error, onSubmit }) => {
  const [body, setBody] = useState("");

  if (!canComment) {
    return (
      <Notice variant="info">Sign in to join this discussion.</Notice>
    );
  }

  const submit = async (event) => {
    event.preventDefault();
    const posted = await onSubmit(body);
    if (posted) setBody("");
  };

  return (
    <Box as="form" onSubmit={submit} sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
      {error && <Notice variant="danger">{error}</Notice>}

      <Textarea
        block
        rows={5}
        value={body}
        maxLength={MAX_BODY}
        aria-label="Write a comment"
        placeholder="Leave a comment. Markdown is supported."
        onChange={(event) => setBody(event.target.value)}
        sx={{ fontFamily: "mono", fontSize: 0 }}
      />

      <Box sx={{ display: "flex", gap: 2, alignItems: "center", flexWrap: "wrap" }}>
        <Button type="submit" variant="primary" disabled={pending || !body.trim()}>
          {pending ? "Posting..." : "Comment"}
        </Button>
        <Text sx={{ fontSize: 0, color: "fg.subtle" }}>Rendered as Markdown.</Text>
      </Box>
    </Box>
  );
};

export default CommentForm;
