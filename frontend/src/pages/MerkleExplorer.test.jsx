import { createRoot } from "react-dom/client";
import { act } from "react-dom/test-utils";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/* Two gaps in jsdom, filled before module evaluation because that is when
   Primer reads them: CSS.supports is asked about dvh units on import, and
   ResizeObserver is used by the branch selector. Neither affects what these
   tests assert. */
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

const historyPage = vi.fn();
const treeFn = vi.fn();
const blobFn = vi.fn();

vi.mock("../services/commitService", () => ({
  commitService: { historyPage: (...a) => historyPage(...a) },
}));
vi.mock("../services/contentService", () => ({
  contentService: { tree: (...a) => treeFn(...a), blob: (...a) => blobFn(...a) },
}));
vi.mock("../services/branchService", () => ({
  branchService: { list: async () => [], head: async () => repository.head },
}));

import { ColorModeContext } from "../context/ColorModeContext.jsx";
import MerkleExplorer from "./MerkleExplorer";

/**
 * The explorer against stubbed services.
 *
 * These cover what a unit test of the traversal logic cannot: that the ids
 * really reach the DOM, that descending asks for the right object, that a tree's
 * id is taken from its parent's listing, and that nothing on the page claims a
 * hash was verified. The live browser QA covers it against the real API.
 */

let host = null;

const SHA = "24c37ca452597eac1aec11bb7d69ec5e5fd17d3c";
const TREE = "7b1c3e10e331cda0fb45c6a58aad17b7203b096f";
const SRC = "08b9195b8619" + "0".repeat(28);
const README = "100f71812793" + "0".repeat(28);

const commitPage = (overrides = {}) => ({
  commits: [
    {
      sha: SHA,
      shortSha: SHA.slice(0, 7),
      message: "Merge, three ways\n",
      tree: TREE,
      parents: ["43b51c8126ff262867eeec5520dd93dc868796f0"],
      ...overrides,
    },
  ],
  hasMore: false,
  nextCursor: null,
});

const rootListing = {
  ref: SHA,
  path: "",
  entries: [
    { name: "LICENSE", path: "LICENSE", type: "file", mode: "100644", id: "a".repeat(40) },
    { name: "README.md", path: "README.md", type: "file", mode: "100644", id: README },
    { name: "src", path: "src", type: "dir", mode: "40000", id: SRC },
  ],
};

const srcListing = {
  ref: SHA,
  path: "src",
  entries: [
    { name: "object", path: "src/object", type: "dir", mode: "40000", id: "b".repeat(40) },
  ],
};

const render = async (initialPath = "/octocat/demo/merkle/main") => {
  host = document.createElement("div");
  document.body.appendChild(host);
  const root = createRoot(host);
  await act(async () => {
    root.render(
      <ColorModeContext.Provider value={{ scheme: "dark", mode: "dark", setMode: () => {} }}>
        <MemoryRouter initialEntries={[initialPath]}>
          <Routes>
            <Route path="/:username/:repo/merkle/:ref" element={<MerkleExplorer />} />
            <Route path="/:username/:repo/merkle" element={<MerkleExplorer />} />
          </Routes>
        </MemoryRouter>
      </ColorModeContext.Provider>,
    );
  });
  return root;
};

/** Every entry control in the listing. */
const entries = () => [...host.querySelectorAll('ul[aria-label^="Entries of"] button')];
const labels = () => entries().map((b) => b.getAttribute("aria-label"));
const text = () => host.textContent;

beforeEach(() => {
  historyPage.mockReset();
  treeFn.mockReset();
  blobFn.mockReset();
  repository.head = { branch: "main", commit: SHA };
  historyPage.mockResolvedValue(commitPage());
  treeFn.mockResolvedValue(rootListing);
  blobFn.mockResolvedValue(null);
});

afterEach(() => {
  if (host) {
    host.remove();
    host = null;
  }
});

describe("MerkleExplorer — the commit and its root tree", () => {
  it("shows the commit id, its root tree id and its parents", async () => {
    await render();

    expect(text()).toContain(SHA.slice(0, 7));
    expect(text()).toContain(TREE);
    expect(text()).toContain("43b51c8126ff262867eeec5520dd93dc868796f0");
  });

  it("pins the walk to the resolved commit, not the ref", async () => {
    /* The point of resolving once: a branch that moves mid-read must not change
       what is being explored underneath the reader. */
    await render();

    expect(treeFn).toHaveBeenCalledWith("octocat", "demo", expect.objectContaining({ ref: SHA }));
  });

  it("lists the root tree's entries with their real ids", async () => {
    await render();

    expect(entries()).toHaveLength(3);
    expect(text()).toContain(SRC.slice(0, 7));
    expect(text()).toContain(README.slice(0, 7));
  });

  it("says a root commit has no parents rather than showing an empty field", async () => {
    historyPage.mockResolvedValue(commitPage({ parents: [] }));

    await render();

    expect(text()).toContain("this is a root commit");
  });
});

describe("MerkleExplorer — descending", () => {
  it("asks for the subtree's own listing when a directory is opened", async () => {
    treeFn.mockImplementation(async (_o, _n, { path }) =>
      path === "src" ? srcListing : rootListing,
    );

    await render("/octocat/demo/merkle/main?path=src");

    // Parent listing (for src's own id) plus src's listing.
    expect(treeFn).toHaveBeenCalledWith("octocat", "demo", expect.objectContaining({ path: "" }));
    expect(treeFn).toHaveBeenCalledWith("octocat", "demo", expect.objectContaining({ path: "src" }));
    expect(entries()).toHaveLength(1);
  });

  it("takes a tree's own id from its parent's entry, since a listing does not report it", async () => {
    /* DirectoryResponse carries ref, path and entries — never the hash of the
       directory being listed. The parent recorded it, which is the Merkle
       relationship this page exists to show. */
    treeFn.mockImplementation(async (_o, _n, { path }) =>
      path === "src" ? srcListing : rootListing,
    );

    await render("/octocat/demo/merkle/main?path=src");

    expect(text()).toContain(SRC);
  });

  it("fetches the blob for a file and shows its size and mode", async () => {
    treeFn.mockResolvedValue(rootListing);
    blobFn.mockResolvedValue({
      path: "README.md",
      id: README,
      mode: "100644",
      size: 413,
      binary: false,
      encoding: "utf-8",
      content: "# demo",
    });

    await render("/octocat/demo/merkle/main?path=README.md");

    expect(blobFn).toHaveBeenCalledWith("octocat", "demo", expect.objectContaining({ path: "README.md" }));
    expect(text()).toContain("413 bytes");
    expect(text()).toContain("100644");
    expect(text()).toContain(README);
  });

  it("does not ask for a listing of a file, which would be a 404", async () => {
    treeFn.mockResolvedValue(rootListing);
    blobFn.mockResolvedValue({ path: "README.md", id: README, mode: "100644", size: 1, binary: false });

    await render("/octocat/demo/merkle/main?path=README.md");

    const listedPaths = treeFn.mock.calls.map((c) => c[2].path);
    expect(listedPaths).not.toContain("README.md");
  });
});

describe("MerkleExplorer — breadcrumb", () => {
  it("shows the chain from the root tree to the current object", async () => {
    treeFn.mockImplementation(async (_o, _n, { path }) =>
      path === "src" ? srcListing : rootListing,
    );

    await render("/octocat/demo/merkle/main?path=src");

    const nav = host.querySelector('nav[aria-label="Object ancestry"]');
    expect(nav).not.toBeNull();
    expect(nav.textContent).toContain("root tree");
    expect(nav.textContent).toContain("src");
  });

  it("links every level except the current one", async () => {
    treeFn.mockImplementation(async (_o, _n, { path }) =>
      path === "src" ? srcListing : rootListing,
    );

    await render("/octocat/demo/merkle/main?path=src");

    const nav = host.querySelector('nav[aria-label="Object ancestry"]');
    const hrefs = [...nav.querySelectorAll("a")].map((a) => a.getAttribute("href"));
    expect(hrefs).toContain("/octocat/demo/merkle/main");
  });

  it("has no links at the root, where there is nowhere further up", async () => {
    await render();

    const nav = host.querySelector('nav[aria-label="Object ancestry"]');
    expect(nav.querySelectorAll("a")).toHaveLength(0);
    expect(nav.textContent).toContain("root tree");
  });
});

describe("MerkleExplorer — states", () => {
  it("shows the empty state for a repository with no commits", async () => {
    repository.head = { branch: "main", commit: null };

    await render();

    expect(text()).toContain("No commits yet");
  });

  it("does not surface the tree 404 as an error on an empty repository", async () => {
    /* GET /tree answers 404 when there are no commits while GET /head answers
       with a null commit. Keying off the commit keeps the ordinary empty state
       from reading as a failure. */
    repository.head = { branch: "main", commit: null };
    treeFn.mockRejectedValue(new Error("Request failed with status code 404"));

    await render();

    expect(text()).toContain("No commits yet");
    expect(text()).not.toMatch(/went wrong|404/i);
  });

  it("shows a not-found state for a path this commit's tree does not contain", async () => {
    treeFn.mockResolvedValue(rootListing);

    await render("/octocat/demo/merkle/main?path=nope");

    expect(text()).toContain("No such object");
    expect(text()).toContain("nope");
  });

  it("shows not-found for a deep path whose parent does not exist either", async () => {
    /* Caught in the browser, not here: `a/b/c` where `a/b` is absent makes the
       parent listing itself 404, so absence arrives as a rejected request rather
       than as an empty result. Reading that as a failure told someone who
       mistyped a path that the application had broken. */
    const notFound = Object.assign(new Error("Request failed"), {
      response: { status: 404, data: { message: "No such directory" } },
    });
    treeFn.mockImplementation(async (_owner, _repo, { path }) => {
      if (path === "does/not") throw notFound;
      return rootListing;
    });

    await render("/octocat/demo/merkle/main?path=does/not/exist");

    expect(text()).toContain("No such object");
    expect(text()).not.toMatch(/went wrong/i);
  });

  it("still surfaces a genuine failure rather than calling it not-found", async () => {
    // Only a 404 means absence. A 500 is the application failing and must say so.
    const broken = Object.assign(new Error("Request failed"), {
      response: { status: 500, data: { message: "Internal error" } },
    });
    treeFn.mockImplementation(async (_owner, _repo, { path }) => {
      if (path === "") throw broken;
      return rootListing;
    });

    await render("/octocat/demo/merkle/main?path=src");

    expect(text()).toMatch(/went wrong|Internal error|Try again/i);
  });

  it("shows an error state when the commit cannot be loaded", async () => {
    historyPage.mockRejectedValue(new Error("network is down"));

    await render();

    expect(text()).toMatch(/went wrong|Try again|network/i);
  });
});

describe("MerkleExplorer — accessibility", () => {
  it("makes every entry a real focusable button", async () => {
    await render();

    for (const button of entries()) {
      expect(button.tagName).toBe("BUTTON");
      expect(button.getAttribute("type")).toBe("button");
    }
  });

  it("names each entry with its kind, name and the id its parent recorded", async () => {
    await render();

    expect(labels()).toContain("Open tree src, object 08b9195");
    expect(labels()).toContain("Inspect file README.md, object 100f718");
  });

  it("gives the listing an accessible name saying which tree it belongs to", async () => {
    await render();

    expect(host.querySelector("ul[aria-label]").getAttribute("aria-label")).toContain("root tree");
  });

  it("labels the ancestry navigation", async () => {
    await render();

    expect(host.querySelector('nav[aria-label="Object ancestry"]')).not.toBeNull();
  });
});

describe("MerkleExplorer — makes no verification claim", () => {
  it("never says an object was verified, checked or validated", async () => {
    /* The framed bytes an id is taken over are not exposed by any endpoint, so
       the browser cannot verify anything. The interface must not imply it did. */
    await render();

    expect(text()).not.toMatch(/verif|validated|checksum ok|integrity confirmed|tamper/i);
  });

  it("describes ids as hashes of content without claiming to have checked one", async () => {
    await render();

    expect(text()).toContain("hash of the object");
  });
});
