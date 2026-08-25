import { Box, Text } from "@primer/react";
import Octicon from "../common/Octicon";
import { GitCommitIcon } from "@primer/octicons-react";

import { formatAbsoluteTime, formatRelativeTime } from "../../utils/dates";
import { subjectOf } from "../../utils/branches";

/**
 * The one-line summary of what a branch points at.
 *
 * Shared by the selector and the branch list so a branch reads the same in both
 * places. The short SHA is monospaced and dimmed: it is an identifier to be
 * recognised at a glance, not read, and giving it the same weight as the
 * message would make every row start with noise.
 */
const BranchMeta = ({ tip, showAuthor = false }) => {
  if (!tip) {
    return (
      <Text sx={{ fontSize: 0, color: "fg.subtle", fontStyle: "italic" }}>
        Its commit could not be read
      </Text>
    );
  }

  return (
    <Box
      sx={{
        display: "flex",
        alignItems: "baseline",
        flexWrap: "wrap",
        gap: 2,
        fontSize: 0,
        color: "fg.muted",
        minWidth: 0,
      }}
    >
      <Box sx={{ display: "inline-flex", alignItems: "center", gap: 1, flexShrink: 0 }}>
        <Octicon icon={GitCommitIcon} size={12} sx={{ color: "fg.subtle" }} />
        <Text sx={{ fontFamily: "mono", color: "fg.muted" }} title={tip.sha}>
          {tip.shortSha}
        </Text>
      </Box>

      {/* The subject is allowed to shrink and clip; the SHA and the date beside
          it are fixed-width facts that must stay whole. */}
      <Text
        sx={{
          minWidth: 0,
          overflow: "hidden",
          textOverflow: "ellipsis",
          whiteSpace: "nowrap",
          color: "fg.default",
        }}
        title={subjectOf(tip.message)}
      >
        {subjectOf(tip.message)}
      </Text>

      <Text sx={{ flexShrink: 0 }} title={formatAbsoluteTime(tip.timestamp)}>
        {showAuthor && tip.authorName ? `${tip.authorName} · ` : ""}
        {formatRelativeTime(tip.timestamp)}
      </Text>
    </Box>
  );
};

export default BranchMeta;
