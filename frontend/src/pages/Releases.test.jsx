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

const list = vi.fn();
const listTags = vi.fn();
const removeTag = vi.fn();
const remove = vi.fn();

vi.mock("../services/releaseService", () => ({
  releaseService: {
    list: (...a) => list(...a),
    listTags: (...a) => listTags(...a),
    removeTag: (...a) => removeTag(...a),
    remove: (...a) => remove(...a),
    get: vi.fn(),
    getTag: vi.fn(),
    createTag: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
  },
}));

import { ColorModeContext } from "../context/ColorModeContext.jsx";
import Releases from "./Releases";

/**
 * The releases page against a stubbed service.
 *
 * These pin the things a wrong page would get wrong: showing a draft to someone
 * who may not see it, offering mutation controls to a reader, or claiming a tag
 * is annotated when the API did not say so. The server enforces all three
 * regardless — the page is a convenience, not the control — but a page that
 * quietly disagreed with the server would be its own kind of defect.
 */

let host = null;
const COMMIT = "a".repeat(40);

const release = (overrides = {}) => ({
  id: "11111111-1111-1111-1111-111111111111",
  tag: "v1.0.0",
  name: "Version 1.0",
  body: "First release.",
  draft: false,
  prerelease: false,
  authorName: "octocat",
  createdAt: "2026-09-01T10:00:00Z",
  updatedAt: "2026-09-01T10:00:00Z",
  publishedAt: "2026-09-01T10:00:00Z",
  ...overrides,
});

const tag = (overrides = {}) => ({
  name: "v1.0.0",
  target: COMMIT,
  commit: COMMIT,
  annotated: false,
  message: null,
  taggerName: null,
  taggerEmail: null,
  taggedAt: null,
  tip: null,
  ...overrides,
});

const render = async () => {
  host = document.createElement("div");
  document.body.appendChild(host);
  const root = createRoot(host);
  await act(async () => {
    root.render(
      <ColorModeContext.Provider value={{ scheme: "dark", mode: "dark", setMode: () => {} }}>
        <MemoryRouter initialEntries={["/octocat/demo/releases"]}>
          <Routes>
            <Route path="/:username/:repo/releases" element={<Releases />} />
          </Routes>
        </MemoryRouter>
      </ColorModeContext.Provider>,
    );
  });
  return root;
};

const text = () => host.textContent;

const buttons = () => [...host.querySelectorAll("button")];

const buttonMatching = (pattern) =>
  buttons().find((button) => pattern.test(button.textContent));

const showTags = async () => {
  await act(async () => {
    buttonMatching(/^Tags$/).click();
  });
};

beforeEach(() => {
  repository.canWrite = false;
  list.mockReset();
  listTags.mockReset();
  removeTag.mockReset();
  remove.mockReset();
  list.mockResolvedValue([release()]);
  listTags.mockResolvedValue([tag()]);
});

afterEach(() => {
  if (host) {
    host.remove();
    host = null;
  }
});

describe("Releases — loading and failure", () => {
  it("shows a loading state before the releases arrive", async () => {
    let settle;
    list.mockReturnValue(new Promise((resolve) => {
      settle = () => resolve([release()]);
    }));

    await render();

    expect(text()).toMatch(/Loading releases/i);

    await act(async () => {
      settle();
    });
  });

  it("reports a failure instead of pretending the list is empty", async () => {
    list.mockRejectedValue(new Error("network"));

    await render();

    expect(text()).toMatch(/could not be loaded/i);
    /* An empty list and a failed request mean different things, and only one of
       them means "nothing has been released". */
    expect(text()).not.toMatch(/No releases yet/i);
  });

  it("offers an empty state when the repository has no releases", async () => {
    list.mockResolvedValue([]);

    await render();

    expect(text()).toMatch(/No releases yet/i);
  });
});

describe("Releases — what it shows", () => {
  it("renders a release with its tag and author", async () => {
    await render();

    expect(text()).toContain("Version 1.0");
    expect(text()).toContain("v1.0.0");
    expect(text()).toContain("octocat");
  });

  it("marks a draft as one", async () => {
    list.mockResolvedValue([release({ draft: true, publishedAt: null })]);

    await render();

    expect(text()).toContain("Draft");
  });

  it("marks a pre-release as one", async () => {
    list.mockResolvedValue([release({ prerelease: true })]);

    await render();

    expect(text()).toContain("Pre-release");
  });

  it("does not invent a draft badge for a published release", async () => {
    await render();

    expect(text()).not.toContain("Draft");
  });

  it("shows nothing about drafts the API did not send", async () => {
    /* Draft visibility is the server's decision; the page renders what it was
       given and never filters on its own, so a listing with no drafts in it
       shows none. */
    list.mockResolvedValue([release(), release({ id: "2", tag: "v2.0.0", name: "Version 2.0" })]);

    await render();

    expect(text()).not.toContain("Draft");
  });
});

describe("Releases — tags", () => {
  it("lists tags on the tags view", async () => {
    await render();
    await showTags();

    expect(text()).toContain("v1.0.0");
    expect(text()).toContain(COMMIT.slice(0, 7));
  });

  it("marks an annotated tag as annotated", async () => {
    listTags.mockResolvedValue([tag({ annotated: true, message: "Release 1.0\n" })]);

    await render();
    await showTags();

    expect(text()).toContain("Annotated");
  });

  it("does not claim a lightweight tag is annotated", async () => {
    await render();
    await showTags();

    expect(text()).not.toContain("Annotated");
  });

  it("offers an empty state when there are no tags", async () => {
    listTags.mockResolvedValue([]);

    await render();
    await showTags();

    expect(text()).toMatch(/No tags yet/i);
  });
});

describe("Releases — owner-only controls", () => {
  it("hides creation and deletion from a reader", async () => {
    await render();

    expect(buttonMatching(/New tag/i)).toBeUndefined();
    expect(buttonMatching(/Draft a release/i)).toBeUndefined();
    expect(buttonMatching(/^Delete$/i)).toBeUndefined();
  });

  it("offers them to the owner", async () => {
    repository.canWrite = true;

    await render();

    expect(buttonMatching(/New tag/i)).toBeDefined();
    expect(buttonMatching(/Draft a release/i)).toBeDefined();
  });

  it("hides tag deletion from a reader", async () => {
    await render();
    await showTags();

    expect(buttonMatching(/^Delete$/i)).toBeUndefined();
  });

  it("does not delete anything without confirmation", async () => {
    repository.canWrite = true;

    await render();
    await act(async () => {
      buttonMatching(/^Delete$/i).click();
    });

    /* The dialog is open; nothing has been sent. A page that deleted on the
       first click would be a page nobody could trust with the button. */
    expect(remove).not.toHaveBeenCalled();
    expect(text()).toMatch(/no history is deleted/i);
  });

  it("says plainly that deleting a tag keeps its commits", async () => {
    repository.canWrite = true;

    await render();
    await showTags();
    await act(async () => {
      buttonMatching(/^Delete$/i).click();
    });

    expect(removeTag).not.toHaveBeenCalled();
    expect(text()).toMatch(/only the reference goes/i);
  });
});
