import { Box } from "@primer/react";

/**
 * Marks the branch HEAD names.
 *
 * Worded rather than ticked, on purpose. The selector already spends a tick on
 * "the branch you are viewing", and those are different states that are often
 * different branches: viewing feature/login while the repository sits on main
 * is normal. Two identical glyphs meaning two different things would be worse
 * than no marker at all.
 */
const CurrentBadge = ({ title = "The repository's current branch" }) => (
  <Box
    as="span"
    title={title}
    sx={{
      flexShrink: 0,
      fontSize: 0,
      lineHeight: 1,
      px: 2,
      py: "3px",
      borderRadius: 999,
      color: "success.fg",
      bg: "success.subtle",
      border: "1px solid",
      borderColor: "success.muted",
      whiteSpace: "nowrap",
    }}
  >
    current
  </Box>
);

export default CurrentBadge;
