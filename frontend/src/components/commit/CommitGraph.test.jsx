import { createRoot } from "react-dom/client";
import { act } from "react-dom/test-utils";
import { afterEach, describe, expect, it, vi } from "vitest";

/* Primer's PageLayout asks CSS.supports about dvh units on import, and jsdom has
   no CSS global at all. Answering "no" before module evaluation is the only
   place this can be done, and is correct for jsdom either way. */
vi.hoisted(() => {
  globalThis.CSS = globalThis.CSS ?? { supports: () => false };
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
});

import { ColorModeContext } from "../../context/ColorModeContext.jsx";
import CommitGraph from "./CommitGraph";
import { GUTTER_METRICS, createGraphMetrics } from "./graphMetrics";
import { buildCommitGraph } from "../../utils/commitGraph";

/**
 * The renderer, which had no test of its own before this version.
 *
 * `buildCommitGraph` is thoroughly covered as pure logic, but nothing checked
 * that what it produces actually reaches the DOM - that an edge becomes a path,
 * that a merge draws differently from an ordinary commit, or that the explorer's
 * larger geometry moves nodes without changing the drawing. Those are the
 * claims here.
 *
 * Rendered with react-dom directly, matching the project's existing component
 * test: there is no rendering-library dependency and this is not the place to
 * introduce one.
 */

let host = null;

const sha = (letter) => letter.repeat(40);

const commit = (letter, parents = []) => ({
  sha: sha(letter),
  shortSha: letter.repeat(7),
  message: `Commit ${letter}\n`,
  parents: parents.map(sha),
  timestamp: `2026-01-0${parents.length + 1}T00:00:00Z`,
  merge: parents.length > 1,
});

/* The renderer reads the resolved scheme for its lane colours: SVG strokes
   cannot read a CSS variable, so the value has to come through context. Supplied
   directly rather than through ColorModeProvider, which would also pull in
   Primer's ThemeProvider and a media-query listener jsdom does not need here. */
const render = (element, scheme = "dark") => {
  host = document.createElement("div");
  document.body.appendChild(host);
  const root = createRoot(host);
  act(() =>
    root.render(
      <ColorModeContext.Provider value={{ scheme, mode: scheme, setMode: () => {} }}>
        {element}
      </ColorModeContext.Provider>,
    ),
  );
  return { root };
};

afterEach(() => {
  if (host) {
    host.remove();
    host = null;
  }
});

describe("CommitGraph", () => {
  it("draws nothing for an empty history without throwing", () => {
    render(<CommitGraph graph={buildCommitGraph([])} />);

    expect(host.querySelector("svg")).not.toBeNull();
    expect(host.querySelectorAll("circle")).toHaveLength(0);
  });

  it("draws a node for every commit", () => {
    const graph = buildCommitGraph([commit("c", ["b"]), commit("b", ["a"]), commit("a")]);

    render(<CommitGraph graph={graph} />);

    // Each node is two circles: the canvas punch-out and the dot itself.
    expect(host.querySelectorAll("circle")).toHaveLength(6);
  });

  it("draws a path for every parent edge inside the window", () => {
    const graph = buildCommitGraph([commit("c", ["b"]), commit("b", ["a"]), commit("a")]);

    render(<CommitGraph graph={graph} />);

    expect(graph.edges).toHaveLength(2);
    // Edges only; the root has no parent and nothing is a boundary here.
    expect(host.querySelectorAll("path")).toHaveLength(2);
  });

  it("draws both edges of a merge", () => {
    //   a
    //  / \
    // b   c
    //  \ /
    //   d
    const graph = buildCommitGraph([
      commit("d", ["b", "c"]),
      commit("b", ["a"]),
      commit("c", ["a"]),
      commit("a"),
    ]);

    render(<CommitGraph graph={graph} />);

    expect(graph.rows.find((node) => node.sha === sha("d")).isMerge).toBe(true);
    expect(graph.edges).toHaveLength(4);
    expect(host.querySelectorAll("path")).toHaveLength(4);
  });

  it("draws a merge as a hollow ring rather than a filled dot", () => {
    /* The one visual distinction that does not rely on colour, since the lanes
       are already using it. The merge is drawn alone so the radii in the
       document can only be its own. */
    const merge = buildCommitGraph([commit("c", ["a", "b"])]);
    render(<CommitGraph graph={merge} />);
    const radii = [...host.querySelectorAll("circle")].map((c) => Number(c.getAttribute("r")));

    expect(merge.rows[0].isMerge).toBe(true);
    expect(radii).toEqual([GUTTER_METRICS.mergeRadius, GUTTER_METRICS.mergeRadius]);
    expect(radii).not.toContain(GUTTER_METRICS.dotRadius);
  });

  it("draws an ordinary commit as a filled dot", () => {
    const ordinary = buildCommitGraph([commit("a")]);
    render(<CommitGraph graph={ordinary} />);
    const radii = [...host.querySelectorAll("circle")].map((c) => Number(c.getAttribute("r")));

    expect(radii).toEqual([GUTTER_METRICS.dotRadius, GUTTER_METRICS.dotRadius]);
  });

  it("draws a boundary stub for a parent outside the window", () => {
    // b's parent was never fetched: that is a stub, not a root.
    const graph = buildCommitGraph([commit("b", ["a"])]);

    render(<CommitGraph graph={graph} />);

    expect(graph.boundaries).toHaveLength(1);
    expect(graph.edges).toHaveLength(0);
    expect(host.querySelector("linearGradient")).not.toBeNull();
  });

  it("is hidden from assistive technology by default", () => {
    // The gutter beside the commit history is decorative; the rows carry the
    // meaning. This is the behaviour the history depends on.
    render(<CommitGraph graph={buildCommitGraph([commit("a")])} />);

    expect(host.querySelector("svg").getAttribute("aria-hidden")).toBe("true");
  });

  it("uses the gutter geometry when no metrics are given", () => {
    const graph = buildCommitGraph([commit("b", ["a"]), commit("a")]);

    render(<CommitGraph graph={graph} />);

    expect(host.querySelector("svg").getAttribute("height")).toBe(
      String(GUTTER_METRICS.graphHeight(2)),
    );
  });

  it("scales to the metrics it is given without changing the drawing", () => {
    /* The explorer's larger canvas must be the same graph at a different size -
       same node count, same edge count, different coordinates. */
    const graph = buildCommitGraph([commit("c", ["b"]), commit("b", ["a"]), commit("a")]);
    const big = createGraphMetrics({ rowHeight: 120, laneWidth: 40 });

    render(<CommitGraph graph={graph} metrics={big} />);

    expect(host.querySelector("svg").getAttribute("height")).toBe(String(big.graphHeight(3)));
    expect(host.querySelectorAll("circle")).toHaveLength(6);
    expect(host.querySelectorAll("path")).toHaveLength(2);
  });
});

describe("createGraphMetrics", () => {
  it("defaults to the gutter's numbers", () => {
    const metrics = createGraphMetrics();

    expect(metrics.rowHeight).toBe(GUTTER_METRICS.rowHeight);
    expect(metrics.laneX(2)).toBe(GUTTER_METRICS.laneX(2));
    expect(metrics.rowY(3)).toBe(GUTTER_METRICS.rowY(3));
  });

  it("overrides only what it is given", () => {
    const metrics = createGraphMetrics({ rowHeight: 100 });

    expect(metrics.rowHeight).toBe(100);
    expect(metrics.laneWidth).toBe(GUTTER_METRICS.laneWidth);
  });

  it("derives positions from the values it was built with", () => {
    const metrics = createGraphMetrics({ rowHeight: 40, laneWidth: 20, paddingX: 10 });

    expect(metrics.rowY(0)).toBe(20);
    expect(metrics.laneX(0)).toBe(10);
    expect(metrics.laneX(3)).toBe(70);
    expect(metrics.graphHeight(4)).toBe(160);
    expect(metrics.graphWidth(3)).toBe(60);
  });

  it("gives an empty graph no height and a single lane no extra width", () => {
    const metrics = createGraphMetrics({ paddingX: 10, laneWidth: 20 });

    expect(metrics.graphHeight(0)).toBe(0);
    expect(metrics.graphWidth(1)).toBe(20);
    expect(metrics.graphWidth(0)).toBe(20);
  });
});
