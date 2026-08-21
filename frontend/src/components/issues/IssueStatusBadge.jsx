import { Box, Octicon } from "@primer/react";
import { CheckIcon, IssueOpenedIcon } from "@primer/octicons-react";

import { IssueStatus } from "../../utils/issues";

/**
 * Whether an issue is open or closed.
 *
 * Closed is slate rather than red. A closed issue is a resolved one, not a
 * failure, and red is reserved here for things that genuinely went wrong or
 * cannot be undone.
 */
const TONE = {
  [IssueStatus.OPEN]: { label: "Open", icon: IssueOpenedIcon, fg: "success.fg", bg: "success.subtle", border: "success.muted" },
  [IssueStatus.CLOSED]: { label: "Closed", icon: CheckIcon, fg: "fg.muted", bg: "neutral.subtle", border: "border.default" },
};

const IssueStatusBadge = ({ status, size = "medium" }) => {
  const tone = TONE[status] ?? TONE[IssueStatus.OPEN];
  const compact = size === "small";

  return (
    <Box
      as="span"
      sx={{
        display: "inline-flex",
        alignItems: "center",
        gap: 1,
        flexShrink: 0,
        px: compact ? 2 : 3,
        py: compact ? "3px" : 1,
        borderRadius: 999,
        fontSize: 0,
        lineHeight: 1.2,
        color: tone.fg,
        bg: tone.bg,
        border: "1px solid",
        borderColor: tone.border,
      }}
    >
      <Octicon icon={tone.icon} size={compact ? 12 : 14} />
      {tone.label}
    </Box>
  );
};

export default IssueStatusBadge;
