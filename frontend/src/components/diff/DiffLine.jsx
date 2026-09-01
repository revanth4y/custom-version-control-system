import { Box } from "@primer/react";

import { diffBackgroundFor, diffForegroundFor } from "../../theme/diffTints";
import { splitBySegments } from "../../utils/diff";

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

/* The approved diff colours, not the generic success and danger tints: the
   palette specifies exact opaque values for these rows in each theme. Being
   opaque, they also solve the sticky-gutter problem outright - there is nothing
   translucent for the scrolling code to show through. */
const toneFor = (scheme) => ({
  ADDED: {
    bg: diffBackgroundFor("added", scheme),
    edge: "success.emphasis",
    sign: "+",
    signColor: diffForegroundFor("added", scheme),
  },
  REMOVED: {
    bg: diffBackgroundFor("removed", scheme),
    edge: "danger.emphasis",
    sign: "-",
    signColor: diffForegroundFor("removed", scheme),
  },
  CONTEXT: { bg: "transparent", edge: "transparent", sign: "", signColor: "fg.subtle" },
});

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
  // Opaque, so the code scrolling underneath cannot show through the gutter.
  backgroundColor: tone.bg === "transparent" ? "canvas.subtle" : tone.bg,
  px: 2,
  whiteSpace: "nowrap",
  overflow: "hidden",
  zIndex: 1,
});

const DiffLine = ({ line, scheme }) => {
  const tones = toneFor(scheme);
  const tone = tones[line.type] ?? tones.CONTEXT;

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
        <LineContent line={line} tone={tone} />
      </Box>
    </Box>
  );
};

/**
 * The code itself, with the characters that actually changed marked.
 *
 * A one-character edit otherwise reads as a whole line removed and a whole line
 * added, leaving the reader to find the difference. The runs come from the
 * server; an older response without them renders as one plain piece, which is
 * exactly what this component did before they existed.
 */
const LineContent = ({ line, tone }) => {
  if (line.content === "") return " ";

  const pieces = splitBySegments(line.content, line.segments);
  if (pieces.length === 1 && !pieces[0].changed) return line.content;

  return pieces.map((piece, index) =>
    piece.changed ? (
      /* `mark` rather than a styled span: the meaning is "this part is the
         point", which is what the element is for, and assistive technology can
         convey it. Weight and underline carry that meaning too, so the marking
         never depends on the colour being perceived. */
      <Box
        as="mark"
        key={index}
        sx={{
          bg: "transparent",
          color: tone.signColor,
          fontWeight: 600,
          borderBottom: "2px solid",
          borderColor: tone.edge,
        }}
      >
        {piece.text}
      </Box>
    ) : (
      <Box as="span" key={index}>
        {piece.text}
      </Box>
    ),
  );
};

export default DiffLine;
