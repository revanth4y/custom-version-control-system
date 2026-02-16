/**
 * Turns a window of commits into rows, lanes and edges for the commit graph.
 *
 * This is a presentation transformation and nothing more. It does not change
 * what history means: the canonical order is whatever the engine returns, and
 * every relationship drawn here comes from the `parents` array of the payload,
 * never from a commit's position in it.
 *
 * The distinction matters because the API returns breadth-first order, which is
 * not a valid order to draw. Breadth-first search reaches a commit by its
 * shortest path, so a commit that is both two hops away on the mainline and
 * three hops away through a branch is emitted at depth two - possibly before
 * the very commit that lists it as a parent. Rendering that order directly
 * draws an edge pointing back up the page.
 */

/** A commit with no parents. The only thing that makes a node a root. */
const isRoot = (commit) => uniqueParents(commit).length === 0;

/**
 * Parent ids with duplicates removed, order preserved.
 *
 * A real commit does not list the same parent twice, but the model permits it,
 * and one edge per distinct parent is what the graph should show either way.
 */
function uniqueParents(commit) {
  const seen = new Set();
  const result = [];
  for (const parent of commit.parents ?? []) {
    if (!seen.has(parent)) {
      seen.add(parent);
      result.push(parent);
    }
  }
  return result;
}

function timeOf(commit) {
  const parsed = Date.parse(commit.timestamp);
  return Number.isNaN(parsed) ? 0 : parsed;
}

/**
 * The order commits are considered in, newest first.
 *
 * Total rather than partial: two distinct commits can share a timestamp - the
 * engine records seconds, as Git does - so the id breaks the tie. Without a
 * total order the output would depend on the order the payload happened to
 * arrive in, and the same history would draw differently between requests.
 */
function preferred(a, b) {
  const byTime = timeOf(b) - timeOf(a);
  if (byTime !== 0) return byTime;
  return a.sha < b.sha ? -1 : a.sha > b.sha ? 1 : 0;
}

function insertSorted(sorted, item) {
  let low = 0;
  let high = sorted.length;
  while (low < high) {
    const mid = (low + high) >> 1;
    if (preferred(sorted[mid], item) <= 0) low = mid + 1;
    else high = mid;
  }
  sorted.splice(low, 0, item);
}

/**
 * Orders commits so that every commit precedes all of its parents.
 *
 * Kahn's algorithm over the window with the edges reversed: a commit becomes
 * eligible once every commit that names it as a parent has been emitted. Among
 * the eligible ones the newest is taken first, which keeps the result close to
 * date order without ever letting a date put a parent above its child - the
 * failure that clock skew and rebases cause when sorting by date alone.
 *
 * Parents outside the window are ignored here. They constrain nothing, because
 * they are not drawn as nodes.
 */
function topologicalOrder(commits) {
  const bySha = new Map(commits.map((commit) => [commit.sha, commit]));
  const childCount = new Map(commits.map((commit) => [commit.sha, 0]));

  for (const commit of commits) {
    for (const parent of uniqueParents(commit)) {
      if (childCount.has(parent)) childCount.set(parent, childCount.get(parent) + 1);
    }
  }

  const eligible = commits.filter((commit) => childCount.get(commit.sha) === 0).sort(preferred);

  const order = [];
  const emitted = new Set();
  while (eligible.length > 0) {
    const next = eligible.shift();
    order.push(next);
    emitted.add(next.sha);

    for (const parentSha of uniqueParents(next)) {
      if (!childCount.has(parentSha)) continue;
      const remaining = childCount.get(parentSha) - 1;
      childCount.set(parentSha, remaining);
      if (remaining === 0) insertSorted(eligible, bySha.get(parentSha));
    }
  }

  // A commit DAG cannot contain a cycle: a commit would have to contain its own
  // hash to close one. If anything is left, the input is not a DAG - show it
  // anyway rather than silently dropping commits, in a defined order.
  if (order.length !== commits.length) {
    const stranded = commits.filter((commit) => !emitted.has(commit.sha)).sort(preferred);
    order.push(...stranded);
  }
  return order;
}

const firstFreeLane = (lanes) => {
  const free = lanes.indexOf(null);
  return free === -1 ? lanes.length : free;
};

/**
 * Places each commit in a column.
 *
 * Walks the display order from the top holding, for each lane, the id of the
 * commit that lane is currently waiting for. A commit takes the lane that was
 * waiting for it, or the leftmost free one if nothing was.
 *
 * The first parent inherits the commit's own lane, which is what keeps a
 * mainline running straight down a single column instead of drifting sideways
 * at every merge. Further parents take their own lanes, so a merge visibly
 * gathers several columns into one row.
 */
function assignLanes(order) {
  const lanes = [];
  const laneOf = new Map();
  let laneCount = 0;

  for (const commit of order) {
    const waiting = lanes.indexOf(commit.sha);
    const lane = waiting === -1 ? firstFreeLane(lanes) : waiting;
    if (lane === lanes.length) lanes.push(null);

    // Every lane waiting for this commit ends here; the extra ones are the
    // branches converging into it.
    for (let i = 0; i < lanes.length; i += 1) {
      if (lanes[i] === commit.sha) lanes[i] = null;
    }
    lanes[lane] = null;
    laneOf.set(commit.sha, lane);

    uniqueParents(commit).forEach((parentSha, index) => {
      const reserved = lanes.indexOf(parentSha);

      if (index === 0) {
        if (reserved === -1) {
          lanes[lane] = parentSha;
        } else if (lane < reserved) {
          // Both a mainline commit and a branch that forked from it name the
          // same first parent, and the branch got there first. Without this the
          // trunk would follow the branch's lane and the graph would have no
          // straight line down it at all - the parent moves to the leftmost
          // claim instead, which is the longer-running line.
          lanes[reserved] = null;
          lanes[lane] = parentSha;
        }
        return;
      }

      if (reserved !== -1) return;
      const free = firstFreeLane(lanes);
      if (free === lanes.length) lanes.push(parentSha);
      else lanes[free] = parentSha;
    });

    while (lanes.length > 0 && lanes[lanes.length - 1] === null) lanes.pop();
    laneCount = Math.max(laneCount, lane + 1, lanes.length);
  }

  return { laneOf, laneCount };
}

/**
 * Builds the drawable graph for a window of commits.
 *
 * Every commit in the window that a parent id points at becomes an edge; every
 * parent id that is *not* in the window becomes a boundary stub instead. A
 * commit is never treated as a root because its parent was not fetched - only
 * an empty parent list makes a root.
 *
 * @param commits the payload of GET /commits, in any order
 */
export function buildCommitGraph(commits) {
  const input = commits ?? [];
  if (input.length === 0) {
    return { rows: [], edges: [], boundaries: [], laneCount: 0, order: [] };
  }

  const bySha = new Map(input.map((commit) => [commit.sha, commit]));
  const order = topologicalOrder(input);
  const { laneOf, laneCount } = assignLanes(order);

  const rowOf = new Map(order.map((commit, row) => [commit.sha, row]));

  const rows = order.map((commit, row) => {
    const parents = uniqueParents(commit);
    return {
      sha: commit.sha,
      shortSha: commit.shortSha,
      commit,
      row,
      lane: laneOf.get(commit.sha),
      parents,
      isMerge: parents.length > 1,
      isRoot: isRoot(commit),
      boundaryParents: parents.filter((parent) => !bySha.has(parent)),
    };
  });

  const edges = [];
  const boundaries = [];

  for (const node of rows) {
    node.parents.forEach((parentSha, parentIndex) => {
      const shared = {
        fromSha: node.sha,
        fromRow: node.row,
        fromLane: node.lane,
        parentIndex,
      };
      if (bySha.has(parentSha)) {
        edges.push({
          ...shared,
          toSha: parentSha,
          toRow: rowOf.get(parentSha),
          toLane: laneOf.get(parentSha),
        });
      } else {
        boundaries.push({ ...shared, parentSha });
      }
    });
  }

  return { rows, edges, boundaries, laneCount, order };
}
