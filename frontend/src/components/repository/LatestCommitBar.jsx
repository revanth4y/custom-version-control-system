import { Link as RouterLink } from "react-router-dom";
import { Box, Link, Text } from "@primer/react";

import IdentityAvatar from "../common/IdentityAvatar";
import { formatRelativeTime } from "../../utils/dates";
import { commitSubject } from "../../utils/treeEntries";

/**
 * The strip above the file table: what happened here most recently.
 *
 * The reference puts the author, the message, the abbreviated sha and the time
 * on one line sitting directly on top of the listing, sharing its border, so
 * the two read as one object rather than two stacked panels.
 *
 * Renders nothing without a commit. A repository with no history has no "most
 * recent" anything, and an empty bar would just be a rule above the table.
 */
const LatestCommitBar = ({ owner, name, commit }) => {
  const subject = commitSubject(commit?.message);
  if (!commit?.sha || !subject) return null;

  return (
    <Box
      sx={{
        display: "flex",
        alignItems: "center",
        gap: 2,
        px: 3,
        py: 2,
        bg: "canvas.inset",
        borderBottom: "1px solid",
        borderColor: "border.muted",
        minWidth: 0,
      }}
    >
      <IdentityAvatar username={commit.authorName} size={20} />

      <Text sx={{ fontSize: 0, fontWeight: 600, color: "fg.default", flexShrink: 0 }}>
        {commit.authorName}
      </Text>

      <Link
        as={RouterLink}
        to={`/${owner}/${name}/commit/${commit.sha}`}
        title={subject}
        sx={{
          fontSize: 0,
          color: "fg.muted",
          minWidth: 0,
          overflow: "hidden",
          textOverflow: "ellipsis",
          whiteSpace: "nowrap",
          "&:hover": { color: "accent.fg", textDecoration: "underline" },
        }}
      >
        {subject}
      </Link>

      <Box sx={{ flex: 1, minWidth: 0 }} />

      <Text
        sx={{
          fontSize: 0,
          fontFamily: "mono",
          color: "fg.subtle",
          flexShrink: 0,
          display: ["none", "inline"],
        }}
      >
        {commit.shortSha}
      </Text>

      <Text sx={{ fontSize: 0, color: "fg.subtle", flexShrink: 0, whiteSpace: "nowrap" }}>
        {formatRelativeTime(commit.timestamp)}
      </Text>
    </Box>
  );
};

export default LatestCommitBar;
