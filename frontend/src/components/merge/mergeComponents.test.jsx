import { createRoot } from "react-dom/client";
import { act } from "react-dom/test-utils";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";

/* The same two jsdom gaps the other component tests fill, before module
   evaluation because that is when Primer reads them. */
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

import ConflictList from "./ConflictList";
import {
  addAddConflict,
  contentConflict,
  contentConflictWithRegions,
  modeConflict,
  modifyDeleteConflict,
  typeConflict,
} from "../../utils/__fixtures__/mergeResponses";

/**
 * The conflict list, rendered.
 *
 * The point of interest is what happens when the engine has established the
 * conflicting line ranges and when it has not: absent regions must render as
 * nothing at all, because an empty list would read as a claim that nothing
 * conflicts rather than that nothing was established.
 */

let host = null;

const render = async (ui) => {
  host = document.createElement("div");
  document.body.appendChild(host);
  const root = createRoot(host);
  await act(async () => {
    root.render(<MemoryRouter>{ui}</MemoryRouter>);
  });
  return host;
};

const list = (response) => (
  <ConflictList
    conflicts={response.conflicts}
    owner="revanth"
    name="advanced-merge-demo"
    target="ready/line-merge"
    source="ready/line-merge-clash"
  />
);

afterEach(() => {
  host?.remove();
  host = null;
});

describe("conflicting regions", () => {
  it("names the disagreeing lines on all three sides", async () => {
    const el = await render(list(contentConflictWithRegions));
    const text = el.textContent;

    expect(text).toContain("One stretch of this file could not be reconciled");
    // Half-open [1,2) is one line, and it is shown as the line a reader counts.
    expect(text).toContain("base");
    expect(text.match(/line 1/g)).toHaveLength(3);
  });

  it("says the rest of the file merged", async () => {
    const el = await render(list(contentConflictWithRegions));

    expect(el.textContent).toContain("The rest of it merged");
  });

  // The distinction the field exists to make. A v2.0.9 response carries no
  // regions at all, and neither does a conflict the line view cannot speak
  // about; both must look exactly as they did before the field existed.
  it("renders nothing where no regions were established", async () => {
    const el = await render(list(contentConflict));

    expect(el.textContent).not.toContain("could not be reconciled");
    expect(el.textContent).not.toContain("The rest of it merged");
  });

  it("renders nothing for conflicts that have no lines to speak of", async () => {
    for (const response of [addAddConflict, modifyDeleteConflict, modeConflict, typeConflict]) {
      const el = await render(list(response));
      expect(el.textContent).not.toContain("could not be reconciled");
      host.remove();
    }
  });
});

describe("existing conflict presentation is unchanged", () => {
  it("still counts the conflicts and labels the kind", async () => {
    const el = await render(list(contentConflictWithRegions));

    expect(el.textContent).toContain("1 conflict");
    expect(el.textContent).toContain("Content");
    expect(el.textContent).toContain("Both sides changed the same file differently.");
  });

  it("still shows all three sides with their object ids", async () => {
    const el = await render(list(contentConflictWithRegions));
    const text = el.textContent;

    expect(text).toContain("common ancestor");
    expect(text).toContain("7522149cdf");
    expect(text).toContain("7fad3c520a");
    expect(text).toContain("2fc1a590b5");
  });

  it("still states which side removed the file on a modify/delete", async () => {
    const el = await render(list(modifyDeleteConflict));

    expect(el.textContent).toContain("not present");
  });

  it("still marks a directory side as a directory", async () => {
    const el = await render(list(typeConflict));

    expect(el.textContent).toContain("directory");
  });

  it("still offers a link to each side that has a file to open", async () => {
    const el = await render(list(contentConflictWithRegions));
    const hrefs = [...el.querySelectorAll("a")].map((a) => a.getAttribute("href"));

    expect(hrefs.some((href) => href.includes("/blob/ready%2Fline-merge/settings.conf"))).toBe(true);
    expect(hrefs.some((href) => href.includes("/compare?"))).toBe(true);
  });
});
