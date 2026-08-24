import {
  BOUNDARY_LENGTH,
  DOT_RADIUS,
  MERGE_RADIUS,
  colorForLane,
  graphHeight,
  graphWidth,
  laneX,
  rowY,
} from "./graphMetrics";
import { palette } from "../../theme/gitforge";
import { useColorModeContext } from "../../context/useColorModeContext";

/**
 * Draws the commit DAG.
 *
 * Deliberately knows nothing about commits beyond a row, a lane and whether a
 * node is a merge. Everything readable - message, author, id - is rendered by
 * the rows beside it in ordinary markup, so the graph never has to reflow text
 * and the text never has to know about geometry.
 *
 * Nothing here decides what connects to what: every line comes from an edge the
 * graph model derived from a real parent id.
 */
const CommitGraph = ({ graph, ariaHidden = true }) => {
  const { scheme } = useColorModeContext();
  const width = graphWidth(graph.laneCount);
  const height = graphHeight(graph.rows.length);

  return (
    <svg
      width={width}
      height={height}
      viewBox={`0 0 ${width} ${height}`}
      aria-hidden={ariaHidden}
      focusable="false"
      data-commit-graph="true"
      style={{ display: "block", flexShrink: 0, overflow: "visible" }}
    >
      {graph.edges.map((edge) => (
        <ParentEdge scheme={scheme} key={`${edge.fromSha}-${edge.toSha}`} edge={edge} />
      ))}

      {graph.boundaries.map((stub) => (
        <BoundaryStub scheme={scheme} key={`${stub.fromSha}-${stub.parentSha}`} stub={stub} />
      ))}

      {graph.rows.map((node) => (
        <CommitNode scheme={scheme} key={node.sha} node={node} />
      ))}
    </svg>
  );
};

/**
 * One parent link.
 *
 * A link that stays in its lane is a straight line. A link that changes lane
 * drops down its own lane first and only bends near the parent, so a branch
 * appears to run alongside the mainline and join it at the end rather than
 * cutting diagonally across every row in between.
 */
const ParentEdge = ({ edge, scheme }) => {
  const x1 = laneX(edge.fromLane);
  const y1 = rowY(edge.fromRow);
  const x2 = laneX(edge.toLane);
  const y2 = rowY(edge.toRow);

  // The colour follows the lane the line spends most of its length in.
  const color = colorForLane(edge.fromLane === edge.toLane ? edge.fromLane : edge.toLane, scheme);
  const merging = edge.parentIndex > 0;

  let d;
  if (x1 === x2) {
    d = `M ${x1} ${y1} L ${x2} ${y2}`;
  } else {
    // Bend just above the parent, with the curve's length capped so it stays a
    // corner on a long edge instead of a lazy diagonal.
    const bend = Math.min(24, Math.abs(y2 - y1) / 2);
    const turn = y2 - bend;
    d = `M ${x1} ${y1} L ${x1} ${turn - bend} C ${x1} ${turn} ${x2} ${turn} ${x2} ${y2}`;
  }

  return (
    <path
      d={d}
      fill="none"
      stroke={color}
      strokeWidth={merging ? 1.5 : 2}
      strokeOpacity={merging ? 0.75 : 1}
      strokeLinecap="round"
    />
  );
};

/**
 * A parent that was not fetched.
 *
 * Drawn as a short stub that stops in open space and fades out, rather than
 * reaching anything. The commit above it has a parent; the graph simply does
 * not have it yet, and saying so is the point - the alternative is a line that
 * ends at nothing and reads as the history's beginning.
 */
const BoundaryStub = ({ stub, scheme }) => {
  const x = laneX(stub.fromLane);
  const y = rowY(stub.fromRow);
  const end = y + BOUNDARY_LENGTH;
  const color = colorForLane(stub.fromLane, scheme);
  const id = `fade-${stub.fromSha}-${stub.parentSha}`;

  return (
    <g>
      <defs>
        <linearGradient id={id} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={color} stopOpacity="0.9" />
          <stop offset="100%" stopColor={color} stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={`M ${x} ${y} L ${x} ${end}`} stroke={`url(#${id})`} strokeWidth="2" fill="none" />
      <path
        d={`M ${x - 3.5} ${end - 1} L ${x} ${end + 3} L ${x + 3.5} ${end - 1}`}
        fill="none"
        stroke={color}
        strokeOpacity="0.55"
        strokeWidth="1.5"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </g>
  );
};

/**
 * A commit.
 *
 * A merge is a hollow ring rather than a filled dot: it reads as a junction
 * where lines meet, and stays distinguishable at a glance without relying on
 * colour, which the lanes are already using.
 */
const CommitNode = ({ node, scheme }) => {
  const canvas = palette[scheme]?.canvas ?? palette.dark.canvas;
  const x = laneX(node.lane);
  const y = rowY(node.row);
  const color = colorForLane(node.lane, scheme);

  if (node.isMerge) {
    return (
      <g>
        <circle cx={x} cy={y} r={MERGE_RADIUS} fill={canvas} />
        <circle cx={x} cy={y} r={MERGE_RADIUS} fill="none" stroke={color} strokeWidth="2.25" />
      </g>
    );
  }

  return (
    <g>
      <circle cx={x} cy={y} r={DOT_RADIUS} fill={canvas} />
      <circle cx={x} cy={y} r={DOT_RADIUS} fill={color} fillOpacity="0.9" stroke={color} strokeWidth="1.5" />
    </g>
  );
};

export default CommitGraph;
