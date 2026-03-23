import { Box } from "@primer/react";

import DiffLine from "./DiffLine";

/**
 * One hunk: the header the engine produced, then its lines.
 *
 * The header is shown verbatim - it is the engine's statement of which line
 * ranges this hunk covers, and rewriting it here would be inventing a claim
 * about the file.
 */
const Hunk = ({ hunk }) => (
  <>
    <Box as="tr">
      <Box
        as="td"
        colSpan={4}
        sx={{
          bg: "canvas.inset",
          color: "fg.muted",
          borderTop: "1px solid",
          borderBottom: "1px solid",
          borderColor: "border.muted",
          px: 3,
          py: 1,
          whiteSpace: "pre",
        }}
      >
        {/* The cell spans the whole table, so making it sticky achieves
            nothing - it is already as wide as the content it would stick
            within. The text inside is what has to hold its position, so the
            range stays readable however far the code is scrolled. */}
        <Box as="span" sx={{ position: "sticky", left: "12px", display: "inline-block" }}>
          {hunk.header}
        </Box>
      </Box>
    </Box>

    {hunk.lines.map((line, index) => (
      <DiffLine key={`${line.type}-${line.oldNumber}-${line.newNumber}-${index}`} line={line} />
    ))}
  </>
);

export default Hunk;
