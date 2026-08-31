import { createRoot } from "react-dom/client";
import { act } from "react-dom/test-utils";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/* Two gaps in jsdom, both filled before module evaluation because that is when
   Primer reads them. `CSS.supports` is asked about dvh units on import, and
   ResizeObserver is used by the branch selector's overflow handling - neither
   exists in jsdom, and neither affects what these tests assert. */
vi.hoisted(() => {
  globalThis.CSS = globalThis.CSS ?? { supports: () => false };
  globalThis.ResizeObserver =
    globalThis.ResizeObserver ??
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    };
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
});

const repository = { owner: "octocat", name: "demo", head: { branch: "main", commit: "c".repeat(40) }, canWrite: false, reloadHead: () => {} };

vi.mock("../hooks/useRepository", () => ({ useRepository: () => repository }));

const historyPage = vi.fn();
const listBranches = vi.fn();

vi.mock("../services/commitService", () => ({
  commitService: { historyPage: (...args) => historyPage(...args) },
}));
vi.mock("../services/branchService", () => ({
  branchService: { list: (...args) => listBranches(...args) },
}));

import { ColorModeContext } from "../context/ColorModeContext.jsx";
import DagExplorer from "./DagExplorer";
import { buildCommitGraph } from "../utils/commitGraph";

/**
 * The explorer, end to end in jsdom.
 *
 * What is worth testing here is what a unit test of the model cannot reach:
 * that the accumulated pages become one graph, that a commit is a real
 * focusable control rather than a decorated div, and that the accessible name
 * carries what the drawing conveys visually. The drawing itself is tested in
 * CommitGraph.test.jsx and the model in commitGraph.test.js.
 */

let host = null;

const sha = (letter) => letter.repeat(40);

const commit = (letter, parents = [], message) => ({
  sha: sha(letter),
  shortSha: letter.repeat(7),
  message: message ?? `Commit ${letter}\n`,
  parents: parents.map(sha),
  authorName: "octocat",
  timestamp: "2026-01-01T00:00:00Z",
  committerName: "octocat",
  committerTimestamp: "2026-01-01T00:00:00Z",
  merge: parents.length > 1,
});

const page = (commits, nextCursor = null) => ({
  commits,
  hasMore: nextCursor !== null,
  nextCursor,
});

const render = async () => {
  host = document.createElement("div");
  document.body.appendChild(host);
  const root = createRoot(host);
  await act(async () => {
    root.render(
      <ColorModeContext.Provider value={{ scheme: "dark", mode: "dark", setMode: () => {} }}>
        <MemoryRouter initialEntries={["/octocat/demo/graph"]}>
          <DagExplorer />
        </MemoryRouter>
      </ColorModeContext.Provider>,
    );
  });
  return root;
};

/** Every commit control, in rendered order. */
const nodes = () => [...host.querySelectorAll('ul[aria-label^="Commit graph"] button')];

const labels = () => nodes().map((button) => button.getAttribute("aria-label"));

beforeEach(() => {
  historyPage.mockReset();
  listBranches.mockReset();
  listBranches.mockResolvedValue([]);
  repository.head = { branch: "main", commit: sha("c") };
});

afterEach(() => {
  if (host) {
    host.remove();
    host = null;
  }
});

describe("DagExplorer — rendering", () => {
  it("renders one control per commit", async () => {
    historyPage.mockResolvedValue(page([commit("c", ["b"]), commit("b", ["a"]), commit("a")]));

    await render();

    expect(nodes()).toHaveLength(3);
  });

  it("asks for a paginated page rather than the bare array", async () => {
    // The bare-array default is V2.0.5's compatibility promise; the explorer
    // must opt in like any other caller.
    historyPage.mockResolvedValue(page([commit("a")]));

    await render();

    expect(historyPage).toHaveBeenCalledWith("octocat", "demo", expect.objectContaining({ ref: "main" }));
  });

  it("draws the graph beside the rows", async () => {
    historyPage.mockResolvedValue(page([commit("b", ["a"]), commit("a")]));

    await render();

    expect(host.querySelector("svg[data-commit-graph]")).not.toBeNull();
  });

  it("shows the empty state for a repository with no commits", async () => {
    repository.head = { branch: "main", commit: null };
    historyPage.mockResolvedValue(page([]));

    await render();

    expect(host.textContent).toContain("No commits yet");
    expect(nodes()).toHaveLength(0);
  });

  it("shows an error state when history cannot be loaded", async () => {
    historyPage.mockRejectedValue(new Error("network is down"));

    await render();

    expect(host.textContent).toMatch(/went wrong|Try again|network/i);
  });

  it("warns when some parents are not loaded", async () => {
    // b's parent was never fetched. Saying so is the point: the alternative is
    // a line that stops at nothing and reads as the start of history.
    historyPage.mockResolvedValue(page([commit("b", ["a"])], "cursor-1"));

    await render();

    expect(host.textContent).toContain("parents that are not loaded");
  });
});

describe("DagExplorer — accessibility", () => {
  it("makes every commit a real focusable button, not a decorated div", async () => {
    historyPage.mockResolvedValue(page([commit("b", ["a"]), commit("a")]));

    await render();

    for (const node of nodes()) {
      expect(node.tagName).toBe("BUTTON");
      expect(node.getAttribute("type")).toBe("button");
    }
  });

  it("names each commit with its short sha and subject", async () => {
    historyPage.mockResolvedValue(page([commit("a", [], "Add the parser\n")]));

    await render();

    expect(labels()[0]).toContain("aaaaaaa");
    expect(labels()[0]).toContain("Add the parser");
  });

  it("says in words that a commit is a merge, which the ring cannot", async () => {
    historyPage.mockResolvedValue(
      page([commit("c", ["a", "b"]), commit("a"), commit("b")]),
    );

    await render();

    const merge = labels().find((label) => label.includes("ccccccc"));
    expect(merge).toContain("merge of 2 parents");
  });

  it("says a root commit has no parents", async () => {
    historyPage.mockResolvedValue(page([commit("a")]));

    await render();

    expect(labels()[0]).toContain("root commit, no parents");
  });

  it("says when a parent has not been loaded, which the faded stub cannot", async () => {
    historyPage.mockResolvedValue(page([commit("b", ["a"])], "cursor-1"));

    await render();

    expect(labels()[0]).toContain("1 parent not loaded yet");
  });

  it("names the branches pointing at a commit, marking HEAD", async () => {
    historyPage.mockResolvedValue(page([commit("c", ["b"]), commit("b")]));
    listBranches.mockResolvedValue([
      { name: "main", commit: sha("c"), head: true, tip: null },
      { name: "side", commit: sha("b"), head: false, tip: null },
    ]);

    await render();

    expect(labels()[0]).toContain("main (HEAD)");
    expect(labels()[1]).toContain("side");
  });

  it("hides the drawing from assistive technology, since the rows carry it", async () => {
    historyPage.mockResolvedValue(page([commit("a")]));

    await render();

    expect(host.querySelector("svg[data-commit-graph]").getAttribute("aria-hidden")).toBe("true");
  });

  it("gives the list an accessible name naming the revision", async () => {
    historyPage.mockResolvedValue(page([commit("a")]));

    await render();

    expect(host.querySelector("ul[aria-label]").getAttribute("aria-label")).toContain("main");
  });
});

describe("DagExplorer — pagination", () => {
  it("appends the next page rather than replacing the first", async () => {
    historyPage
      .mockResolvedValueOnce(page([commit("c", ["b"])], "cursor-1"))
      .mockResolvedValueOnce(page([commit("b", ["a"]), commit("a")]));

    await render();
    expect(nodes()).toHaveLength(1);

    const loadMore = [...host.querySelectorAll("button")].find((b) =>
      b.textContent.includes("Load more history"),
    );
    await act(async () => loadMore.dispatchEvent(new MouseEvent("click", { bubbles: true })));

    expect(nodes()).toHaveLength(3);
  });

  it("sends the cursor it was given, unchanged", async () => {
    historyPage
      .mockResolvedValueOnce(page([commit("c", ["b"])], "opaque-cursor"))
      .mockResolvedValueOnce(page([commit("b")]));

    await render();
    const loadMore = [...host.querySelectorAll("button")].find((b) =>
      b.textContent.includes("Load more history"),
    );
    await act(async () => loadMore.dispatchEvent(new MouseEvent("click", { bubbles: true })));

    expect(historyPage).toHaveBeenLastCalledWith(
      "octocat",
      "demo",
      expect.objectContaining({ cursor: "opaque-cursor" }),
    );
  });

  it("offers no way to load more once the history ends", async () => {
    historyPage.mockResolvedValue(page([commit("a")]));

    await render();

    expect([...host.querySelectorAll("button")].some((b) => b.textContent.includes("Load more"))).toBe(
      false,
    );
    expect(host.textContent).toContain("the whole history from here");
  });

  it("shows no commit twice across pages", async () => {
    historyPage
      .mockResolvedValueOnce(page([commit("c", ["b"])], "cursor-1"))
      .mockResolvedValueOnce(page([commit("b", ["a"]), commit("a")]));

    await render();
    const loadMore = [...host.querySelectorAll("button")].find((b) =>
      b.textContent.includes("Load more history"),
    );
    await act(async () => loadMore.dispatchEvent(new MouseEvent("click", { bubbles: true })));

    const shas = labels().map((label) => label.split(".")[0]);
    expect(new Set(shas).size).toBe(shas.length);
  });

  it("keeps what is drawn when loading more fails", async () => {
    historyPage
      .mockResolvedValueOnce(page([commit("c", ["b"])], "cursor-1"))
      .mockRejectedValueOnce(new Error("network is down"));

    await render();
    const loadMore = [...host.querySelectorAll("button")].find((b) =>
      b.textContent.includes("Load more history"),
    );
    await act(async () => loadMore.dispatchEvent(new MouseEvent("click", { bubbles: true })));

    expect(nodes()).toHaveLength(1);
    expect(host.textContent).toContain("Could not load more history");
  });
});

describe("late-arriving parents", () => {
  /* The correctness claim behind accumulating pages: a parent that was outside
     the first window is a boundary stub, and must become a real edge once the
     page carrying it arrives - not stay a stub, and not be drawn twice. Tested
     against the model directly, because it is the model that must get it right;
     the explorer only feeds it the accumulated list. */

  it("draws a boundary while the parent is unloaded", () => {
    const first = buildCommitGraph([commit("c", ["b"])]);

    expect(first.boundaries).toHaveLength(1);
    expect(first.edges).toHaveLength(0);
    expect(first.rows[0].boundaryParents).toEqual([sha("b")]);
  });

  it("converts that boundary into a real edge when the parent arrives", () => {
    const accumulated = buildCommitGraph([commit("c", ["b"]), commit("b", ["a"]), commit("a")]);

    expect(accumulated.boundaries).toHaveLength(0);
    expect(accumulated.edges).toHaveLength(2);
    expect(accumulated.edges.some((e) => e.fromSha === sha("c") && e.toSha === sha("b"))).toBe(true);
  });

  it("does not duplicate the edge it converted", () => {
    const accumulated = buildCommitGraph([commit("c", ["b"]), commit("b", ["a"]), commit("a")]);
    const seen = accumulated.edges.map((e) => `${e.fromSha}->${e.toSha}`);

    expect(new Set(seen).size).toBe(seen.length);
  });

  it("still shows every commit exactly once after accumulating", () => {
    const accumulated = buildCommitGraph([commit("c", ["b"]), commit("b", ["a"]), commit("a")]);
    const shas = accumulated.rows.map((row) => row.sha);

    expect(new Set(shas).size).toBe(3);
  });

  it("orders the accumulated graph the same way whatever order the pages arrive in", () => {
    // Determinism across paging: the same commits must draw the same graph.
    const forwards = buildCommitGraph([commit("c", ["b"]), commit("b", ["a"]), commit("a")]);
    const shuffled = buildCommitGraph([commit("a"), commit("c", ["b"]), commit("b", ["a"])]);

    expect(shuffled.rows.map((r) => r.sha)).toEqual(forwards.rows.map((r) => r.sha));
    expect(shuffled.rows.map((r) => r.lane)).toEqual(forwards.rows.map((r) => r.lane));
  });
});
