import { Box } from "@primer/react";

import { tokens } from "../../theme/gitforge";

/**
 * One line of a hunk, exactly as the engine classified it.
 *
 * The two number columns and the sign stick to the left edge, so a line long
 * enough to scroll takes only its code with it and stays labelled. All rows
 * share one scroller, which is what keeps the columns lined up with each other
 * rather than each row drifting independently.
 *
 * The gutters are fixed widths rather than sized to their contents, because a
 * sticky element needs an exact offset: with content-sized columns the offsets
 * drift out of step and the sign column ends up painted over the code.
 */
export const OLD_GUTTER = 48;
export const NEW_GUTTER = 48;
export const SIGN_GUTTER = 20;

const TONE = {
  ADDED: { bg: "success.subtle", tint: tokens.successSubtle, edge: "success.emphasis", sign: "+", signColor: "success.fg" },
  REMOVED: { bg: "danger.subtle", tint: tokens.dangerSubtle, edge: "danger.emphasis", sign: "-", signColor: "danger.fg" },
  CONTEXT: { bg: "transparent", tint: null, edge: "transparent", sign: "", signColor: "fg.subtle" },
};

const gutter = (tone, width, left) => ({
  // Copying a snippet should not take the line numbers with it.
  userSelect: "none",
  position: "sticky",
  left: `${left}px`,
  width: `${width}px`,
  minWidth: `${width}px`,
  maxWidth: `${width}px`,
  textAlign: "right",
  verticalAlign: "top",
  color: "fg.subtle",
  // The row tints are translucent, so using one directly here let the code
  // show through the gutter as it scrolled underneath. The tint is composited
  // over an opaque base instead, which looks identical and hides what passes
  // behind it.
  backgroundColor: "canvas.subtle",
  backgroundImage: tone.tint ? `linear-gradient(${tone.tint}, ${tone.tint})` : "none",
  px: 2,
  whiteSpace: "nowrap",
  overflow: "hidden",
  zIndex: 1,
});

const DiffLine = ({ line }) => {
  const tone = TONE[line.type] ?? TONE.CONTEXT;

  return (
    <Box as="tr" sx={{ bg: tone.bg }}>
      <Box as="td" sx={gutter(tone, OLD_GUTTER, 0)}>
        {line.oldNumber ?? ""}
      </Box>

      <Box
        as="td"
        sx={{
          ...gutter(tone, NEW_GUTTER, OLD_GUTTER),
          borderRight: "1px solid",
          borderColor: "border.muted",
        }}
      >
        {line.newNumber ?? ""}
      </Box>

      <Box
        as="td"
        aria-hidden="true"
        sx={{
          ...gutter(tone, SIGN_GUTTER, OLD_GUTTER + NEW_GUTTER),
          textAlign: "center",
          color: tone.signColor,
          borderLeft: "2px solid",
          borderColor: tone.edge,
          px: 0,
        }}
      >
        {tone.sign}
      </Box>

      <Box
        as="td"
        sx={{
          px: 2,
          // The code keeps its own spacing and does not wrap; the file's
          // scroller handles anything wider than the panel.
          whiteSpace: "pre",
          color: "fg.default",
          verticalAlign: "top",
        }}
      >
        {line.content === "" ? " " : line.content}
      </Box>
    </Box>
  );
};

export default DiffLine;
