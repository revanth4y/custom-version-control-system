import { Box, Text, Label, Link } from "@primer/react";

import IdentityAvatar from "../common/IdentityAvatar";
import { ROW_HEIGHT } from "./graphMetrics";
import { formatAbsoluteTime, formatRelativeTime } from "../../utils/dates";
import { subjectOf } from "../../utils/branches";

/**
 * One commit, beside the graph.
 *
 * Fixed to exactly ROW_HEIGHT. The graph places its dot at the centre of the
 * same band, so the two stay aligned without either measuring the other; a row
 * that grew with its content would pull every dot below it out of place.
 */
const CommitRow = ({ node, onSelect }) => {
  const { commit, isMerge, boundaryParents } = node;
  const subject = subjectOf(commit.message);

  return (
    <Box
      data-commit-row={node.sha}
      sx={{
        height: `${ROW_HEIGHT}px`,
        display: "flex",
        alignItems: "center",
        gap: 3,
        px: 3,
        minWidth: 0,
        borderTop: node.row === 0 ? "none" : "1px solid",
        borderColor: "border.muted",
        "&:hover": { bg: "canvas.inset" },
      }}
    >
      {/* The author is named on the line below anyway; on a phone the avatar
          costs width the commit id needs more. */}
      <Box sx={{ display: ["none", "flex"], flexShrink: 0 }}>
        <IdentityAvatar username={commit.authorName} size={24} />
      </Box>

      <Box sx={{ minWidth: 0, flex: 1 }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 2, minWidth: 0 }}>
          <Text
            sx={{
              fontSize: 1,
              fontWeight: 600,
              // One line: the subject is the summary, and a wrapping one would
              // change the row height the graph depends on.
              overflow: "hidden",
              textOverflow: "ellipsis",
              whiteSpace: "nowrap",
              minWidth: 0,
            }}
            title={commit.message}
          >
            {subject}
          </Text>

          {isMerge && (
            <Label
              sx={{ flexShrink: 0, color: "fg.muted", borderColor: "border.default" }}
              title={`Merge of ${node.parents.length} parents`}
            >
              merge
            </Label>
          )}
        </Box>

        <Text sx={{ fontSize: 0, color: "fg.muted", display: "block", mt: "2px" }}>
          {commit.authorName} committed{" "}
          <Text as="span" title={formatAbsoluteTime(commit.timestamp)}>
            {formatRelativeTime(commit.timestamp)}
          </Text>
          {boundaryParents.length > 0 && (
            <Text as="span" sx={{ color: "fg.subtle" }}>
              {" "}
              · {boundaryParents.length === 1 ? "parent" : "parents"} not loaded
            </Text>
          )}
        </Text>
      </Box>

      <Link
        as="button"
        type="button"
        onClick={() => onSelect?.(commit)}
        sx={{
          fontFamily: "mono",
          fontSize: 0,
          color: "fg.muted",
          flexShrink: 0,
          bg: "transparent",
          border: "1px solid",
          borderColor: "border.default",
          borderRadius: 2,
          px: 2,
          py: 1,
          cursor: "pointer",
          "&:hover": { color: "accent.fg", borderColor: "accent.emphasis" },
        }}
        title={commit.sha}
        aria-label={`Commit ${commit.shortSha}`}
      >
        {commit.shortSha}
      </Link>
    </Box>
  );
};

export default CommitRow;
