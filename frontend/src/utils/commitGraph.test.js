import { describe, expect, it } from "vitest";

import { buildCommitGraph } from "./commitGraph";
import { dagDemoFull, dagDemoLimit4, dagDemoLimit6 } from "./__fixtures__/dagDemoHistory";

/** Builds a commit payload of the shape GET /commits returns. */
const commit = (sha, parents = [], secondsFromEpoch = 0) => ({
  sha,
  shortSha: sha.slice(0, 7),
  message: `commit ${sha}\n`,
  authorName: "dev",
  timestamp: new Date(secondsFromEpoch * 1000).toISOString(),
  parents,
  merge: parents.length > 1,
});

/**
 * Every property the graph must hold for any input, checked together.
 *
 * Applied to every fixture rather than spot-checked, because most of these can
 * only break in combination - a lane collision, for instance, shows up as two
 * nodes sharing a coordinate long before it looks wrong on screen.
 */
function expectGraphInvariants(graph, commits) {
  const { rows, edges, boundaries } = graph;
  const present = new Set(commits.map((c) => c.sha));
  const rowBySha = new Map(rows.map((r) => [r.sha, r]));

  // Every commit appears exactly once.
  expect(rows).toHaveLength(commits.length);
  expect(new Set(rows.map((r) => r.sha)).size).toBe(commits.length);

  // Rows are numbered 0..n-1 in order, and no two nodes share a coordinate.
  expect(rows.map((r) => r.row)).toEqual(commits.map((_, i) => i));
  const coordinates = rows.map((r) => `${r.row}:${r.lane}`);
  expect(new Set(coordinates).size).toBe(rows.length);
  for (const r of rows) expect(r.lane).toBeGreaterThanOrEqual(0);
  expect(Math.max(-1, ...rows.map((r) => r.lane))).toBeLessThan(graph.laneCount);

  // The edge set is exactly the parent relation restricted to the window, and
  // every boundary is exactly a parent that was not fetched.
  const expectedEdges = new Set();
  const expectedBoundaries = new Set();
  for (const c of commits) {
    for (const p of [...new Set(c.parents)]) {
      (present.has(p) ? expectedEdges : expectedBoundaries).add(`${c.sha}->${p}`);
    }
  }
  expect(new Set(edges.map((e) => `${e.fromSha}->${e.toSha}`))).toEqual(expectedEdges);
  expect(new Set(boundaries.map((b) => `${b.fromSha}->${b.parentSha}`))).toEqual(expectedBoundaries);
  expect(edges).toHaveLength(expectedEdges.size);
  expect(boundaries).toHaveLength(expectedBoundaries.size);

  for (const edge of edges) {
    const child = rowBySha.get(edge.fromSha);
    const parent = rowBySha.get(edge.toSha);
    // Endpoints resolve to real nodes, and carry those nodes' coordinates.
    expect(child).toBeDefined();
    expect(parent).toBeDefined();
    expect(edge.fromRow).toBe(child.row);
    expect(edge.fromLane).toBe(child.lane);
    expect(edge.toRow).toBe(parent.row);
    expect(edge.toLane).toBe(parent.lane);
    // The ordering guarantee the whole layout rests on.
    expect(edge.fromRow).toBeLessThan(edge.toRow);
    // The edge is a real parent link, at the parent index it claims.
    expect([...new Set(child.commit.parents)][edge.parentIndex]).toBe(edge.toSha);
  }

  for (const stub of boundaries) {
    expect(present.has(stub.parentSha)).toBe(false);
    const child = rowBySha.get(stub.fromSha);
    expect(stub.fromRow).toBe(child.row);
    expect(stub.fromLane).toBe(child.lane);
  }

  // One edge or stub per distinct parent - merges included, and nothing else.
  for (const node of rows) {
    const drawn =
      edges.filter((e) => e.fromSha === node.sha).length +
      boundaries.filter((b) => b.fromSha === node.sha).length;
    expect(drawn).toBe(new Set(node.commit.parents).size);
    if (node.isMerge) expect(drawn).toBeGreaterThanOrEqual(2);
    // Only an empty parent list makes a root. A missing parent never does.
    expect(node.isRoot).toBe((node.commit.parents ?? []).length === 0);
  }
}

/** Deterministic shuffle, so a failure can be reproduced from its seed. */
function shuffle(items, seed) {
  const out = [...items];
  let state = seed;
  const next = () => {
    state = (state * 1664525 + 1013904223) >>> 0;
    return state / 0x100000000;
  };
  for (let i = out.length - 1; i > 0; i -= 1) {
    const j = Math.floor(next() * (i + 1));
    [out[i], out[j]] = [out[j], out[i]];
  }
  return out;
}

const orderOf = (graph) => graph.rows.map((r) => r.sha);

describe("buildCommitGraph — shapes", () => {
  it("handles an empty window", () => {
    const graph = buildCommitGraph([]);
    expect(graph).toEqual({ rows: [], edges: [], boundaries: [], laneCount: 0, order: [] });
  });

  it("handles a single root commit", () => {
    const commits = [commit("a", [], 1)];
    const graph = buildCommitGraph(commits);
    expectGraphInvariants(graph, commits);
    expect(graph.rows[0].isRoot).toBe(true);
    expect(graph.laneCount).toBe(1);
    expect(graph.edges).toHaveLength(0);
  });

  it("draws linear history as a single lane, newest first", () => {
    const commits = [commit("c", ["b"], 3), commit("b", ["a"], 2), commit("a", [], 1)];
    const graph = buildCommitGraph(commits);
    expectGraphInvariants(graph, commits);
    expect(orderOf(graph)).toEqual(["c", "b", "a"]);
    expect(graph.laneCount).toBe(1);
    expect(graph.rows.every((r) => r.lane === 0)).toBe(true);
  });

  it("gives diverged branches separate lanes", () => {
    // two tips over a shared base
    const commits = [commit("x", ["base"], 3), commit("y", ["base"], 2), commit("base", [], 1)];
    const graph = buildCommitGraph(commits);
    expectGraphInvariants(graph, commits);
    expect(graph.laneCount).toBe(2);
    const lanes = new Map(graph.rows.map((r) => [r.sha, r.lane]));
    expect(lanes.get("x")).not.toBe(lanes.get("y"));
  });

  it("gives a two-parent merge two edges from two distinct lanes", () => {
    const commits = [
      commit("m", ["p1", "p2"], 4),
      commit("p1", ["base"], 3),
      commit("p2", ["base"], 2),
      commit("base", [], 1),
    ];
    const graph = buildCommitGraph(commits);
    expectGraphInvariants(graph, commits);

    const merge = graph.rows.find((r) => r.sha === "m");
    expect(merge.isMerge).toBe(true);
    const fromMerge = graph.edges.filter((e) => e.fromSha === "m");
    expect(fromMerge.map((e) => e.toSha)).toEqual(["p1", "p2"]);
    expect(new Set(fromMerge.map((e) => e.toLane)).size).toBe(2);
    // The first parent keeps the merge's own lane, so the mainline runs straight.
    expect(fromMerge[0].toLane).toBe(merge.lane);
  });

  it("gives an octopus merge one edge per parent", () => {
    const commits = [
      commit("m", ["p1", "p2", "p3"], 5),
      commit("p1", ["base"], 4),
      commit("p2", ["base"], 3),
      commit("p3", ["base"], 2),
      commit("base", [], 1),
    ];
    const graph = buildCommitGraph(commits);
    expectGraphInvariants(graph, commits);
    const fromMerge = graph.edges.filter((e) => e.fromSha === "m");
    expect(fromMerge.map((e) => e.toSha)).toEqual(["p1", "p2", "p3"]);
    expect(new Set(fromMerge.map((e) => e.toLane)).size).toBe(3);
  });

  it("draws a shared ancestor once, however many paths reach it", () => {
    const commits = [
      commit("m", ["l", "r"], 4),
      commit("l", ["base"], 3),
      commit("r", ["base"], 2),
      commit("base", [], 1),
    ];
    const graph = buildCommitGraph(commits);
    expectGraphInvariants(graph, commits);
    expect(graph.rows.filter((r) => r.sha === "base")).toHaveLength(1);
    expect(graph.edges.filter((e) => e.toSha === "base")).toHaveLength(2);
  });

  it("collapses a duplicated parent id to one edge", () => {
    const commits = [commit("m", ["p", "p"], 2), commit("p", [], 1)];
    const graph = buildCommitGraph(commits);
    expectGraphInvariants(graph, commits);
    expect(graph.edges).toHaveLength(1);
  });
});

describe("buildCommitGraph — window boundaries", () => {
  it("represents a parent outside the window as a stub, not a node", () => {
    const commits = [commit("c", ["b"], 2), commit("b", ["missing"], 1)];
    const graph = buildCommitGraph(commits);
    expectGraphInvariants(graph, commits);

    expect(graph.rows.map((r) => r.sha)).not.toContain("missing");
    expect(graph.boundaries).toHaveLength(1);
    expect(graph.boundaries[0]).toMatchObject({ fromSha: "b", parentSha: "missing" });
  });

  it("does not call a commit a root just because its parent was not fetched", () => {
    const commits = [commit("b", ["missing"], 1)];
    const graph = buildCommitGraph(commits);
    expect(graph.rows[0].isRoot).toBe(false);
    expect(graph.rows[0].boundaryParents).toEqual(["missing"]);
  });

  it("stubs each missing parent of a merge separately", () => {
    const commits = [commit("m", ["gone1", "gone2"], 1)];
    const graph = buildCommitGraph(commits);
    expectGraphInvariants(graph, commits);
    expect(graph.edges).toHaveLength(0);
    expect(graph.boundaries.map((b) => b.parentSha)).toEqual(["gone1", "gone2"]);
    expect(graph.rows[0].isMerge).toBe(true);
  });
});

describe("buildCommitGraph — recorded history from the running engine", () => {
  it("satisfies every invariant on the full 14-commit history", () => {
    const graph = buildCommitGraph(dagDemoFull);
    expectGraphInvariants(graph, dagDemoFull);
    expect(graph.rows.filter((r) => r.isMerge)).toHaveLength(3);
    expect(graph.rows.filter((r) => r.isRoot)).toHaveLength(2);
    expect(graph.boundaries).toHaveLength(0);
  });

  /**
   * The reason this module exists.
   *
   * The engine returns breadth-first order, in which these two commits are
   * listed *before* the commits that name them as parents. Drawing the payload
   * in the order it arrives points those edges up the page. If this test ever
   * fails, something has started trusting list position again.
   */
  it("corrects the breadth-first order the API actually returns", () => {
    const positionInPayload = new Map(dagDemoFull.map((c, i) => [c.sha, i]));
    const violations = [];
    for (const c of dagDemoFull) {
      for (const p of c.parents) {
        if (positionInPayload.has(p) && positionInPayload.get(p) < positionInPayload.get(c.sha)) {
          violations.push([c.sha, p]);
        }
      }
    }
    // The recorded payload really does contain the problem.
    expect(violations).toHaveLength(2);

    const graph = buildCommitGraph(dagDemoFull);
    const rowOf = new Map(graph.rows.map((r) => [r.sha, r.row]));
    for (const [child, parent] of violations) {
      expect(rowOf.get(child)).toBeLessThan(rowOf.get(parent));
    }
  });

  /**
   * Found by looking at the rendered graph, not by reasoning about it: the
   * trunk was stepping between two columns because a branch that forked from a
   * commit reserved that commit's lane before the mainline reached it.
   */
  it("keeps the first-parent chain in one lane", () => {
    const graph = buildCommitGraph(dagDemoFull);
    const bySha = new Map(graph.rows.map((r) => [r.sha, r]));

    let node = graph.rows[0];
    const lanes = new Set([node.lane]);
    while (node && node.parents.length > 0) {
      node = bySha.get(node.parents[0]);
      if (node) lanes.add(node.lane);
    }
    expect([...lanes]).toEqual([0]);
  });

  it("satisfies every invariant on truncated windows", () => {
    for (const window of [dagDemoLimit4, dagDemoLimit6]) {
      expectGraphInvariants(buildCommitGraph(window), window);
    }
    expect(buildCommitGraph(dagDemoLimit4).boundaries).toHaveLength(2);
    // Both parents of the merge at the edge of this window are outside it.
    expect(buildCommitGraph(dagDemoLimit6).boundaries).toHaveLength(2);
  });

  it("keeps every commit of a larger window when the window grows", () => {
    const small = new Set(buildCommitGraph(dagDemoLimit4).rows.map((r) => r.sha));
    const large = new Set(buildCommitGraph(dagDemoFull).rows.map((r) => r.sha));
    for (const sha of small) expect(large.has(sha)).toBe(true);
  });
});

describe("buildCommitGraph — determinism", () => {
  it("produces identical output for 100 shuffled input orders", () => {
    const reference = buildCommitGraph(dagDemoFull);
    const expected = JSON.stringify({
      rows: reference.rows.map((r) => [r.sha, r.row, r.lane]),
      edges: reference.edges.map((e) => [e.fromSha, e.toSha, e.fromLane, e.toLane]),
      laneCount: reference.laneCount,
    });

    for (let seed = 1; seed <= 100; seed += 1) {
      const graph = buildCommitGraph(shuffle(dagDemoFull, seed));
      const actual = JSON.stringify({
        rows: graph.rows.map((r) => [r.sha, r.row, r.lane]),
        edges: graph.edges.map((e) => [e.fromSha, e.toSha, e.fromLane, e.toLane]),
        laneCount: graph.laneCount,
      });
      expect(actual, `seed ${seed}`).toBe(expected);
    }
  });

  it("orders commits sharing a timestamp by id, not by arrival", () => {
    // The engine records seconds, so ties are ordinary rather than exotic.
    const tied = [commit("bbb", ["aaa"], 5), commit("ccc", ["aaa"], 5), commit("aaa", [], 4)];
    const forward = orderOf(buildCommitGraph(tied));
    const backward = orderOf(buildCommitGraph([...tied].reverse()));
    expect(forward).toEqual(backward);
    expect(forward.slice(0, 2)).toEqual(["bbb", "ccc"]);
  });

  it("does not mutate the array it is given", () => {
    const commits = [commit("c", ["b"], 3), commit("b", ["a"], 2), commit("a", [], 1)];
    const before = commits.map((c) => c.sha);
    buildCommitGraph(commits);
    expect(commits.map((c) => c.sha)).toEqual(before);
  });
});

describe("buildCommitGraph — hostile input", () => {
  it("keeps every commit even if the input is not a DAG", () => {
    // Impossible for real content-addressed commits, but the renderer must not
    // silently drop rows if it ever happens.
    const cyclic = [commit("a", ["b"], 2), commit("b", ["a"], 1)];
    const graph = buildCommitGraph(cyclic);
    expect(graph.rows).toHaveLength(2);
    expect(new Set(graph.rows.map((r) => r.sha))).toEqual(new Set(["a", "b"]));
  });

  it("treats an unparseable timestamp as oldest rather than throwing", () => {
    const commits = [commit("b", ["a"], 2), { ...commit("a", [], 1), timestamp: "not a date" }];
    const graph = buildCommitGraph(commits);
    expectGraphInvariants(graph, commits);
  });

  it("tolerates a missing parents field", () => {
    const commits = [{ sha: "a", shortSha: "a", timestamp: "2026-01-01T00:00:00Z" }];
    const graph = buildCommitGraph(commits);
    expect(graph.rows[0].isRoot).toBe(true);
    expect(graph.edges).toHaveLength(0);
  });
});
