import { createRoot } from "react-dom/client";
import { act } from "react-dom/test-utils";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/* The same two jsdom gaps the other page tests fill, before module evaluation
   because that is when Primer reads them. */
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

const repository = {
  owner: "octocat",
  name: "demo",
  head: { branch: "main", commit: "c".repeat(40) },
  canWrite: false,
  reloadHead: () => {},
};

vi.mock("../hooks/useRepository", () => ({ useRepository: () => repository }));

const forRepository = vi.fn();
const activity = vi.fn();
const commits = vi.fn();
const commitSeries = vi.fn();
const contributors = vi.fn();
const branches = vi.fn();
const refs = vi.fn();
const tags = vi.fn();
const storage = vi.fn();
const health = vi.fn();

vi.mock("../services/insightsService", () => ({
  insightsService: {
    forRepository: (...a) => forRepository(...a),
    activity: (...a) => activity(...a),
    commits: (...a) => commits(...a),
    commitSeries: (...a) => commitSeries(...a),
    contributors: (...a) => contributors(...a),
    branches: (...a) => branches(...a),
    refs: (...a) => refs(...a),
    tags: (...a) => tags(...a),
    releases: vi.fn(),
    issues: vi.fn(),
    storage: (...a) => storage(...a),
    health: (...a) => health(...a),
  },
}));

import { ColorModeContext } from "../context/ColorModeContext.jsx";
import Insights from "./Insights";

/**
 * The Insights page against a stubbed API.
 *
 * These pin the things a wrong page would get wrong: showing a figure the API
 * did not return, disagreeing with a number it did return, or — the one that
 * costs the server rather than the reader — running the health scan without
 * being asked. The scan takes the repository's exclusive lock, so "it only runs
 * on the button" is a correctness property, not a preference.
 */

let host = null;
const COMMIT = "a".repeat(40);

const OVERVIEW = {
  commits: 12,
  branches: 3,
  files: 7,
  storedObjects: 40,
  contributors: [
    { name: "Ada", email: "ada@example.test", commits: 9 },
    { name: "Linus", email: "linus@example.test", commits: 3 },
  ],
  activity: [
    { date: "2026-08-01", count: 4 },
    { date: "2026-08-02", count: 5 },
    { date: "2026-08-03", count: 2 },
    { date: "2026-08-09", count: 1 },
  ],
};

const DAG = {
  commits: 12,
  merges: 2,
  nonMerges: 10,
  mergeRatio: 0.1667,
  roots: 1,
  rootCommits: [COMMIT],
  maxDepth: 8,
  maxParents: 2,
  earliestCommit: "2026-08-01T09:00:00Z",
  latestCommit: "2026-08-09T09:00:00Z",
  historyDurationSeconds: 691_200,
};

const REFS = {
  branches: 3,
  tags: 2,
  remoteTrackingRefs: 1,
  remotes: 1,
  total: 6,
  headAttached: true,
  headBranch: "main",
  commitsOnlyTagsProtect: 4,
};

const SERIES = {
  from: "2026-08-01",
  to: "2026-08-04",
  bucket: "day",
  total: 11,
  points: [
    { date: "2026-08-01", count: 4 },
    { date: "2026-08-02", count: 5 },
    { date: "2026-08-03", count: 2 },
    { date: "2026-08-04", count: 0 },
  ],
};

const CHEAP_HEALTH = {
  storedObjects: 40,
  roots: 5,
  scanned: false,
  reachableObjects: null,
  unreachableObjects: null,
  unreachableBytes: null,
  retained: null,
  scanTruncated: null,
  fullyReachable: null,
  scanDurationMs: null,
  integrity: "NOT_VERIFIED",
  verifiedObjects: 0,
  damagedObjects: 0,
  integrityTruncated: false,
};

const SCANNED_HEALTH = {
  ...CHEAP_HEALTH,
  scanned: true,
  reachableObjects: 38,
  unreachableObjects: 2,
  unreachableBytes: 2048,
  retained: 0,
  scanTruncated: false,
  fullyReachable: false,
  scanDurationMs: 17,
  integrity: "HEALTHY",
  verifiedObjects: 40,
  damagedObjects: 0,
};

const STORAGE = {
  storedObjects: 40,
  scannedObjects: 40,
  scannedBytes: 4096,
  truncated: false,
  unreadable: 0,
  byType: [
    { type: "blob", count: 20, bytes: 2048 },
    { type: "tree", count: 12, bytes: 1024 },
    { type: "commit", count: 6, bytes: 768 },
    { type: "tag", count: 2, bytes: 256 },
  ],
};

const render = async () => {
  host = document.createElement("div");
  document.body.appendChild(host);
  const root = createRoot(host);
  await act(async () => {
    root.render(
      <ColorModeContext.Provider value={{ scheme: "dark", mode: "dark", setMode: () => {} }}>
        <MemoryRouter initialEntries={["/octocat/demo/insights"]}>
          <Routes>
            <Route path="/:username/:repo/insights" element={<Insights />} />
          </Routes>
        </MemoryRouter>
      </ColorModeContext.Provider>,
    );
  });
  return root;
};

const text = () => host.textContent;

const buttons = () => [...host.querySelectorAll("button")];

const buttonMatching = (pattern) => buttons().find((button) => pattern.test(button.textContent));

const openTab = async (label) => {
  await act(async () => {
    buttons()
      .find((button) => button.getAttribute("role") === "tab" && button.textContent === label)
      .click();
  });
};

beforeEach(() => {
  repository.head = { branch: "main", commit: "c".repeat(40) };
  for (const stub of [
    forRepository,
    activity,
    commits,
    commitSeries,
    contributors,
    branches,
    refs,
    tags,
    storage,
    health,
  ]) {
    stub.mockReset();
  }

  forRepository.mockResolvedValue(OVERVIEW);
  commits.mockResolvedValue(DAG);
  refs.mockResolvedValue(REFS);
  commitSeries.mockResolvedValue(SERIES);
  activity.mockResolvedValue({
    from: "2026-08-01",
    to: "2026-08-04",
    commits: 11,
    merges: 2,
    contributors: 2,
    issuesOpened: 3,
    issuesClosed: 1,
    releasesPublished: 1,
    tagsCreated: 2,
  });
  contributors.mockResolvedValue({
    from: "2026-08-01",
    to: "2026-08-04",
    total: 2,
    contributors: [
      {
        name: "Ada",
        email: "ada@example.test",
        commits: 9,
        merges: 1,
        firstCommit: "2026-08-01",
        lastCommit: "2026-08-03",
      },
      {
        name: "Linus",
        email: "linus@example.test",
        commits: 3,
        merges: 0,
        firstCommit: "2026-08-02",
        lastCommit: "2026-08-02",
      },
    ],
  });
  branches.mockResolvedValue({
    base: COMMIT,
    total: 2,
    branches: [
      { name: "main", tip: COMMIT, ahead: 0, behind: 0, current: true, related: true },
      { name: "feature", tip: "b".repeat(40), ahead: 3, behind: 1, current: false, related: true },
    ],
  });
  tags.mockResolvedValue({
    total: 2,
    annotated: 1,
    lightweight: 1,
    medianIntervalSeconds: 86_400,
    firstTagged: "2026-08-02T09:00:00Z",
    lastTagged: "2026-08-03T09:00:00Z",
    withoutRelease: ["v0.9.0"],
    tags: [
      {
        name: "v1.0.0",
        annotated: true,
        target: COMMIT,
        commit: COMMIT,
        taggedAt: "2026-08-03T09:00:00Z",
      },
      { name: "v0.9.0", annotated: false, target: COMMIT, commit: COMMIT, taggedAt: null },
    ],
  });
  health.mockResolvedValue(CHEAP_HEALTH);
  storage.mockResolvedValue(STORAGE);
});

afterEach(() => {
  if (host) {
    host.remove();
    host = null;
  }
});

describe("Insights — structure", () => {
  it("offers exactly the six named tabs, in order", async () => {
    await render();

    const labels = [...host.querySelectorAll('[role="tab"]')].map((tab) => tab.textContent);
    expect(labels).toEqual([
      "Overview",
      "Activity",
      "Commits",
      "Contributors",
      "Refs",
      "Health",
    ]);
  });

  it("says there is nothing to measure when the repository has no commits", async () => {
    repository.head = null;
    await render();

    expect(text()).toContain("Nothing to measure yet");
    expect(forRepository).not.toHaveBeenCalled();
  });
});

describe("Insights — Overview", () => {
  it("reports the API's own figures, not derived ones", async () => {
    await render();

    // Total commits, contributors, branches, tags, merges.
    expect(text()).toContain("Total commits");
    expect(text()).toContain("12");
    expect(text()).toContain("Contributors");
    expect(text()).toContain("Merge commits");
    expect(text()).toContain("Repository age");
    expect(text()).toContain("Active days");
    expect(text()).toContain("Average commits/day");
  });

  it("counts active days from the days that carry commits", async () => {
    await render();

    // Four dated entries in the activity list, not the nine calendar days they span.
    const card = [...host.querySelectorAll("div")].find(
      (node) => node.textContent.startsWith("ACTIVE DAYS") || node.textContent.startsWith("Active days"),
    );
    expect(card?.textContent).toContain("4");
  });

  /*
   * The averaging window is written out in words, so it has to agree with the
   * number in front of it. A single-day history is the common case for a young
   * repository and is exactly where "1 days" would show.
   */
  it("says 'days' when the history spans more than one", async () => {
    await render();

    // 2026-08-01 to 2026-08-09 inclusive.
    expect(text()).toContain("over the 9 days from first to latest commit");
  });

  it("says 'day' when the history spans exactly one", async () => {
    commits.mockResolvedValue({
      ...DAG,
      earliestCommit: "2026-08-09T09:00:00Z",
      latestCommit: "2026-08-09T17:00:00Z",
    });

    await render();

    expect(text()).toContain("over the 1 day from first to latest commit");
    expect(text()).not.toContain("1 days");
  });
});

describe("Insights — Activity", () => {
  it("draws every bucket, including the empty ones", async () => {
    await render();
    await openTab("Activity");

    expect(host.querySelectorAll("svg rect")).toHaveLength(SERIES.points.length);
    expect(text()).toContain("11 in 4 periods");
  });

  it("asks the server for weekly buckets when weekly is chosen", async () => {
    await render();
    await openTab("Activity");

    await act(async () => {
      buttonMatching(/^Weekly$/).click();
    });

    expect(commitSeries).toHaveBeenCalledWith(
      "octocat",
      "demo",
      expect.objectContaining({ bucket: "week" }),
    );
  });

  it("sends no date parameters until a range is applied", async () => {
    await render();
    await openTab("Activity");

    expect(commitSeries).toHaveBeenCalledWith(
      "octocat",
      "demo",
      expect.objectContaining({ from: "", to: "" }),
    );
  });
});

describe("Insights — Commits", () => {
  it("separates merges from single-parent commits", async () => {
    await render();
    await openTab("Commits");

    expect(text()).toContain("Normal commits");
    expect(text()).toContain("Merge commits");
  });

  it("finds the busiest day and the longest consecutive run", async () => {
    await render();
    await openTab("Commits");

    // 5 commits on 2026-08-02 is the peak; 08-01..08-03 is a run of three, and
    // the isolated 08-09 does not extend it.
    expect(text()).toContain("on 2026-08-02");
    expect(text()).toContain("3 d");
    expect(text()).toContain("ending 2026-08-03");
  });
});

describe("Insights — Contributors", () => {
  it("lists each author with their share of the window", async () => {
    await render();
    await openTab("Contributors");

    expect(text()).toContain("Ada");
    expect(text()).toContain("ada@example.test");
    expect(text()).toContain("9 commits");
    // 9 of the 12 commits in the window.
    expect(text()).toContain("75.0%");
  });

  it("preserves the server's ordering", async () => {
    await render();
    await openTab("Contributors");

    expect(text().indexOf("Ada")).toBeLessThan(text().indexOf("Linus"));
  });

  /* The merge count sits inline in a sentence, so it has to read as one. */
  it("says 'merge' for one and 'merges' for several", async () => {
    contributors.mockResolvedValue({
      from: "2026-08-01",
      to: "2026-08-04",
      total: 2,
      contributors: [
        {
          name: "Ada",
          email: "ada@example.test",
          commits: 9,
          merges: 2,
          firstCommit: "2026-08-01",
          lastCommit: "2026-08-03",
        },
        {
          name: "Linus",
          email: "linus@example.test",
          commits: 3,
          merges: 1,
          firstCommit: "2026-08-02",
          lastCommit: "2026-08-02",
        },
      ],
    });

    await render();
    await openTab("Contributors");

    expect(text()).toContain("2 merges");
    expect(text()).toContain("1 merge");
    // "1 merge" alone is a substring of "1 merges", so this is what pins it.
    expect(text()).not.toContain("1 merges");
  });

  it("says nothing about merges for an author who has none", async () => {
    await render();
    await openTab("Contributors");

    // Ada has one merge in the default fixture; Linus has none.
    expect(text()).toContain("1 merge");
    expect(text()).not.toContain("0 merge");
  });
});

describe("Insights — Refs", () => {
  it("shows the reference counts and each branch's distance from HEAD", async () => {
    await render();
    await openTab("Refs");

    expect(text()).toContain("Remote branches");
    expect(text()).toContain("3 ahead");
    expect(text()).toContain("1 behind");
    expect(text()).toContain("main");
  });

  it("says a lightweight tag has no tagging time rather than inventing one", async () => {
    await render();
    await openTab("Refs");

    expect(text()).toContain("no tagging time recorded");
  });
});

describe("Insights — Health", () => {
  it("never scans on arrival", async () => {
    await render();
    await openTab("Health");

    expect(health).toHaveBeenCalledWith("octocat", "demo");
    expect(health).not.toHaveBeenCalledWith("octocat", "demo", { scan: true });
    expect(storage).not.toHaveBeenCalled();
    expect(text()).toContain("Nothing has been scanned yet");
  });

  it("reports unmeasured figures as unmeasured, not as zero", async () => {
    await render();
    await openTab("Health");

    expect(text()).toContain("Not scanned");
    expect(text()).toContain("NOT_VERIFIED");
  });

  it("scans only when the button is pressed", async () => {
    health.mockResolvedValueOnce(CHEAP_HEALTH).mockResolvedValueOnce(SCANNED_HEALTH);

    await render();
    await openTab("Health");

    await act(async () => {
      buttonMatching(/Run health scan/).click();
    });

    expect(health).toHaveBeenCalledWith("octocat", "demo", { scan: true });
    expect(storage).toHaveBeenCalledWith("octocat", "demo");
    expect(text()).toContain("HEALTHY");
    expect(text()).toContain("Dangling objects");
    expect(text()).not.toContain("Nothing has been scanned yet");
  });

  it("shows the type breakdown only after a scan", async () => {
    health.mockResolvedValueOnce(CHEAP_HEALTH).mockResolvedValueOnce(SCANNED_HEALTH);

    await render();
    await openTab("Health");
    expect(text()).toContain("blob objects");

    await act(async () => {
      buttonMatching(/Run health scan/).click();
    });

    // 20 blobs, and the repository size the same scan measured.
    expect(text()).toContain("20");
    expect(text()).toContain("4 KB");
  });
});
