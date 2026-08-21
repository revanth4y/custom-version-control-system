import { Link as RouterLink } from "react-router-dom";
import { Box, Link, Text, Octicon } from "@primer/react";
import { CheckIcon, CommentIcon, IssueOpenedIcon } from "@primer/octicons-react";

import { IssueStatus, authorLabel } from "../../utils/issues";
import { formatAbsoluteTime, formatRelativeTime } from "../../utils/dates";

/**
 * One issue in the list.
 *
 * The icon carries the state so a row reads at a glance without the badge that
 * the detail page uses; the title is the link, and everything else is context.
 */
const IssueRow = ({ owner, name, issue, first }) => {
  const open = issue.status === IssueStatus.OPEN;

  return (
    <Box
      sx={{
        display: "flex",
        alignItems: "flex-start",
        gap: 2,
        px: 3,
        py: 3,
        minWidth: 0,
        borderTop: first ? "none" : "1px solid",
        borderColor: "border.muted",
        "&:hover": { bg: "canvas.inset" },
      }}
    >
      <Octicon
        icon={open ? IssueOpenedIcon : CheckIcon}
        sx={{ color: open ? "success.fg" : "fg.muted", mt: "2px", flexShrink: 0 }}
        aria-label={open ? "Open" : "Closed"}
      />

      <Box sx={{ minWidth: 0, flex: 1 }}>
        <Link
          as={RouterLink}
          to={`/${owner}/${name}/issues/${issue.number}`}
          sx={{
            fontSize: 1,
            fontWeight: 600,
            color: "fg.default",
            // A long title wraps rather than pushing the row wider.
            overflowWrap: "anywhere",
            "&:hover": { color: "accent.fg" },
          }}
        >
          {issue.title}
        </Link>

        <Text sx={{ display: "block", fontSize: 0, color: "fg.muted", mt: 1 }}>
          <Text as="span" sx={{ fontFamily: "mono" }}>#{issue.number}</Text>
          {" · "}
          {open ? "opened" : "closed"}{" "}
          <Text as="span" title={formatAbsoluteTime(issue.createdAt)}>
            {formatRelativeTime(issue.createdAt)}
          </Text>
          {" by "}
          <Text as="span" sx={{ color: issue.authorUsername ? "fg.muted" : "fg.subtle" }}>
            {authorLabel(issue.authorUsername)}
          </Text>
        </Text>
      </Box>
    </Box>
  );
};

export default IssueRow;
