import { laneColors } from "../../theme/gitforge";

/**
 * The geometry shared by the graph and the commit rows beside it.
 *
 * ROW_HEIGHT is the one number both sides must agree on: the SVG places a node
 * at row * ROW_HEIGHT + half, and each row element is given exactly that height.
 * If the two ever disagree the dots drift away from their commits, so the value
 * lives here and neither side is allowed a literal of its own.
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
