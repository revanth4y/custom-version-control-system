import { useEffect, useRef } from "react";
import { Box, Text } from "@primer/react";

import { busiestDay, describeDay, intensityOf, monthLabels, toWeeks } from "../../utils/contributions";
import { brand, palette } from "../../theme/gitforge";
import { useColorModeContext } from "../../context/useColorModeContext";

const CELL = 11;
const GAP = 3;
const COLUMN = CELL + GAP;
const ROWS = 7;
const LABEL_HEIGHT = 16;

/**
 * The brand green at four strengths over an empty square.
 *
 * A single hue rather than a spectrum: the scale is one quantity getting
 * larger, and colours that change hue imply categories that are not there.
 *
 * Built per theme because the empty square has to be the theme's own muted
 * border - on white, a dark empty square would read as the busiest day rather
 * than the quietest.
 */
const shadesFor = (scheme) => [
  palette[scheme].borderMuted,
  "rgba(34, 197, 94, 0.28)",
  "rgba(34, 197, 94, 0.50)",
  "rgba(34, 197, 94, 0.74)",
  brand.accent,
];

/**
 * A year of commits, one square per day.
 *
 * Every day comes from the server, empty ones included, so nothing here decides
 * what happened on a date. The squares only say how much, relative to the
 * busiest day in the window.
 *
 * Each square is a real `<title>` inside the SVG rather than a hover-only
 * tooltip, so the count and date are available to a screen reader and to anyone
 * not using a mouse.
 */
const ContributionGraph = ({ contributions }) => {
  const { scheme } = useColorModeContext();
  const SHADES = shadesFor(scheme);
  const days = contributions?.days ?? [];
  const weeks = toWeeks(days);
  const max = busiestDay(days);
  const labels = monthLabels(weeks);
  const scroller = useRef(null);

  // Opens on the present. A year is wider than a phone, and the interesting
  // end is the recent one.
  useEffect(() => {
    if (scroller.current) scroller.current.scrollLeft = scroller.current.scrollWidth;
  }, [weeks.length]);

  if (days.length === 0) {
    return (
      <Text sx={{ fontSize: 0, color: "fg.subtle" }}>No activity to show for this period.</Text>
    );
  }

  const width = weeks.length * COLUMN;
  const height = ROWS * COLUMN + LABEL_HEIGHT;

  return (
    <Box>
      {/* The calendar scrolls inside itself; the page never widens for it. */}
      <Box ref={scroller} sx={{ overflowX: "auto", pb: 1 }}>
        <svg
          width={width}
          height={height}
          viewBox={`0 0 ${width} ${height}`}
          role="img"
          aria-label={`${contributions.total} commits between ${contributions.from} and ${contributions.to}`}
          style={{ display: "block" }}
        >
          {labels.map(({ index, label }) => (
            <text
              key={`${label}-${index}`}
              x={index * COLUMN}
              y={11}
              fill="currentColor"
              fontSize="10"
            >
              {label}
            </text>
          ))}

          {weeks.map((week, column) =>
            week.map((day, row) =>
              day ? (
                <rect
                  key={day.date}
                  x={column * COLUMN}
                  y={LABEL_HEIGHT + row * COLUMN}
                  width={CELL}
                  height={CELL}
                  rx="2"
                  fill={SHADES[intensityOf(day.count, max)]}
                >
                  <title>{describeDay(day)}</title>
                </rect>
              ) : null,
            ),
          )}
        </svg>
      </Box>

      <Box
        sx={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          gap: 3,
          flexWrap: "wrap",
          mt: 2,
        }}
      >
        <Text sx={{ fontSize: 0, color: "fg.muted" }}>
          {contributions.total} {contributions.total === 1 ? "commit" : "commits"} in the last year
        </Text>

        <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
          <Text sx={{ fontSize: 0, color: "fg.subtle" }}>Less</Text>
          {SHADES.map((shade, level) => (
            <Box
              key={shade}
              aria-hidden="true"
              sx={{ width: `${CELL}px`, height: `${CELL}px`, borderRadius: 1, bg: shade }}
              title={level === 0 ? "No commits" : `Level ${level} of ${SHADES.length - 1}`}
            />
          ))}
          <Text sx={{ fontSize: 0, color: "fg.subtle" }}>More</Text>
        </Box>
      </Box>
    </Box>
  );
};

export default ContributionGraph;
