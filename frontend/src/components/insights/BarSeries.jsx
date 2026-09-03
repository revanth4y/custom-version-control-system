import { useState } from "react";
import { Box, Text } from "@primer/react";
import { useColorModeContext } from "../../context/useColorModeContext";

/**
 * A bar chart for a gap-filled series.
 *
 * Hand-built SVG rather than a charting dependency, following ContributionGraph:
 * the shapes needed here are rectangles and a baseline, and a library would add
 * a second styling system to keep in step with the design tokens.
 *
 * Every bucket the API returned is drawn, including the empty ones. A chart that
 * skipped quiet periods would compress time silently and say something untrue
 * about the repository.
 */
const HEIGHT = 140;
const MIN_BAR = 1;

const BarSeries = ({ points, label = "Commits", emptyLabel = "No activity in this period" }) => {
  const { scheme } = useColorModeContext();
  const [hovered, setHovered] = useState(null);

  const data = points ?? [];
  const max = data.reduce((highest, point) => Math.max(highest, point.count), 0);
  const total = data.reduce((sum, point) => sum + point.count, 0);

  if (data.length === 0) {
    return (
      <Text sx={{ fontSize: 1, color: "fg.muted" }}>{emptyLabel}</Text>
    );
  }

  const fill = scheme === "dark" ? "#3fb950" : "#2da44e";
  const emptyFill = scheme === "dark" ? "#21262d" : "#eaeef2";

  // Widths are percentages so the chart fills whatever column it is given and
  // needs no measurement or resize listener to stay responsive.
  const slot = 100 / data.length;
  const active = hovered === null ? null : data[hovered];

  return (
    <Box>
      <Box
        sx={{
          position: "relative",
          height: `${HEIGHT}px`,
          borderBottom: "1px solid",
          borderColor: "border.default",
        }}
      >
        <svg
          viewBox="0 0 100 100"
          preserveAspectRatio="none"
          width="100%"
          height={HEIGHT}
          role="img"
          aria-label={`${label} over ${data.length} periods, ${total} in total`}
          style={{ display: "block" }}
        >
          {data.map((point, index) => {
            const ratio = max === 0 ? 0 : (point.count / max) * 100;
            const height = point.count === 0 ? MIN_BAR : Math.max(ratio, MIN_BAR + 1);
            return (
              <rect
                key={point.date}
                x={index * slot}
                y={100 - height}
                width={Math.max(slot * 0.8, 0.4)}
                height={height}
                fill={point.count === 0 ? emptyFill : fill}
                onMouseEnter={() => setHovered(index)}
                onMouseLeave={() => setHovered(null)}
              />
            );
          })}
        </svg>
      </Box>

      <Box
        sx={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          gap: 2,
          mt: 2,
          minHeight: "20px",
          flexWrap: "wrap",
        }}
      >
        <Text sx={{ fontSize: 0, color: "fg.muted" }}>{data[0].date}</Text>
        <Text sx={{ fontSize: 0, color: "fg.default", fontWeight: 600 }} data-testid="series-tooltip">
          {active
            ? `${active.date}: ${active.count} ${active.count === 1 ? "commit" : "commits"}`
            : `${total} in ${data.length} ${data.length === 1 ? "period" : "periods"}`}
        </Text>
        <Text sx={{ fontSize: 0, color: "fg.muted" }}>{data[data.length - 1].date}</Text>
      </Box>

      {/* The numbers behind the bars, for anyone a bar height tells nothing. */}
      <Box as="table" sx={{ position: "absolute", width: "1px", height: "1px", overflow: "hidden", clip: "rect(0 0 0 0)" }}>
        <caption>{label} by period</caption>
        <tbody>
          {data.map((point) => (
            <tr key={point.date}>
              <th scope="row">{point.date}</th>
              <td>{point.count}</td>
            </tr>
          ))}
        </tbody>
      </Box>
    </Box>
  );
};

export default BarSeries;
