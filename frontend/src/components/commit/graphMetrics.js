import { laneColors } from "../../theme/gitforge";

/**
 * The geometry shared by the graph and the commit rows beside it.
 *
 * ROW_HEIGHT is the one number both sides must agree on: the SVG places a node
 * at row * ROW_HEIGHT + half, and each row element is given exactly that height.
 * If the two ever disagree the dots drift away from their commits, so the value
 * lives here and neither side is allowed a literal of its own.
 *
 * These are the gutter's numbers, tuned for the narrow strip beside the commit
 * history. The explorer needs the same geometry at a different scale, so the
 * shapes below are also available through {@link createGraphMetrics}; these
 * exports are that factory's default and stay exactly what they always were.
 */
export const ROW_HEIGHT = 68;
export const LANE_WIDTH = 18;
export const GRAPH_PADDING_X = 14;
export const DOT_RADIUS = 4.5;
export const MERGE_RADIUS = 6;
export const BOUNDARY_LENGTH = 16;

/** Beyond this the gutter would crowd out the message; the rest still draw, scrolled. */
export const MAX_COMFORTABLE_LANES = 5;

export const laneX = (lane) => GRAPH_PADDING_X + lane * LANE_WIDTH;
export const rowY = (row) => row * ROW_HEIGHT + ROW_HEIGHT / 2;

export const graphWidth = (laneCount) =>
  GRAPH_PADDING_X * 2 + Math.max(0, laneCount - 1) * LANE_WIDTH;

export const graphHeight = (rowCount) => Math.max(rowCount, 0) * ROW_HEIGHT;

/** Colour by lane index, so a line keeps its colour for as long as it exists. */
export const colorForLane = (lane, scheme = "dark") => {
  const lanes = laneColors[scheme] ?? laneColors.dark;
  return lanes[lane % lanes.length];
};

/**
 * The same geometry at a different scale.
 *
 * One set of shapes, parameterised - not a second layout algorithm. The gutter
 * and the explorer draw the identical graph and differ only in how much room
 * they have for it, so the numbers vary and nothing else does. Two renderers
 * with their own arithmetic would be two things that could disagree about where
 * a node sits, and the row markup beside them cannot follow both.
 *
 * @param overrides any of the metric values above
 * @returns the derived helpers, bound to those values
 */
export const createGraphMetrics = (overrides = {}) => {
  const rowHeight = overrides.rowHeight ?? ROW_HEIGHT;
  const laneWidth = overrides.laneWidth ?? LANE_WIDTH;
  const paddingX = overrides.paddingX ?? GRAPH_PADDING_X;
  const dotRadius = overrides.dotRadius ?? DOT_RADIUS;
  const mergeRadius = overrides.mergeRadius ?? MERGE_RADIUS;
  const boundaryLength = overrides.boundaryLength ?? BOUNDARY_LENGTH;

  return {
    rowHeight,
    laneWidth,
    paddingX,
    dotRadius,
    mergeRadius,
    boundaryLength,
    laneX: (lane) => paddingX + lane * laneWidth,
    rowY: (row) => row * rowHeight + rowHeight / 2,
    graphWidth: (laneCount) => paddingX * 2 + Math.max(0, laneCount - 1) * laneWidth,
    graphHeight: (rowCount) => Math.max(rowCount, 0) * rowHeight,
  };
};

/** The gutter's metrics, as an object - the default for {@link createGraphMetrics}. */
export const GUTTER_METRICS = createGraphMetrics();
