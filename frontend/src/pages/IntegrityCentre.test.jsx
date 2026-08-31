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

vi.mock("../services/integrityService", () => ({
  integrityService: { forRepository: (...a) => forRepository(...a) },
}));

import { ColorModeContext } from "../context/ColorModeContext.jsx";
import IntegrityCentre from "./IntegrityCentre";

/**
 * The Integrity Centre against a stubbed service.
 *
 * What these pin is the reporting, not the hashing: the verification is the
 * server's, and a test that stubbed a hash would prove nothing about it. So they
 * assert the things a wrong page would get wrong — claiming health that was never
 * established, running an expensive scan uninvited, or rendering a count the API
 * did not send.
 */

let host = null;

const DAMAGED_ID = "a22a2da24d1ceeef3d0c2f1f4f68923f55b8d4cc";
const SECOND_ID = "100f7181279366df0feadf3bcdff115e0cdf3bf7";

const report = (overrides = {}) => ({
  storedObjects: 12,
  verified: 12,
  damaged: [],
  healthy: true,
  truncated: false,
  checkedAt: "2026-08-31T18:00:00Z",
  durationMs: 37,
  ...overrides,
});

const render = async () => {
  host = document.createElement("div");
  document.body.appendChild(host);
  const root = createRoot(host);
  await act(async () => {
    root.render(
      <ColorModeContext.Provider value={{ scheme: "dark", mode: "dark", setMode: () => {} }}>
        <MemoryRouter initialEntries={["/octocat/demo/integrity"]}>
          <Routes>
            <Route path="/:username/:repo/integrity" element={<IntegrityCentre />} />
          </Routes>
        </MemoryRouter>
      </ColorModeContext.Provider>,
    );
  });
  return root;
};

const text = () => host.textContent;

const runButton = () =>
  [...host.querySelectorAll("button")].find((button) => /Run check/i.test(button.textContent));

const runCheck = async () => {
  await act(async () => {
    runButton().click();
  });
};

beforeEach(() => {
  forRepository.mockReset();
  repository.head = { branch: "main", commit: "c".repeat(40) };
  forRepository.mockResolvedValue(report());
});

afterEach(() => {
  if (host) {
    host.remove();
    host = null;
  }
});

describe("IntegrityCentre — before it is asked", () => {
  it("does not run the scan on load", async () => {
    /* The one read whose cost scales with what the repository holds. Opening a
       tab must not start it. */
    await render();

    expect(forRepository).not.toHaveBeenCalled();
  });

  it("offers a native button to run it", async () => {
    await render();

    expect(runButton()).toBeTruthy();
    expect(runButton().tagName).toBe("BUTTON");
  });

  it("says nothing has been verified rather than implying it passed", async () => {
    await render();

    expect(text()).toContain("Nothing has been verified yet");
    expect(text()).not.toMatch(/re-hashed to the ids/i);
  });

  it("announces the result in a polite live region", async () => {
    await render();

    expect(host.querySelector('[aria-live="polite"]')).toBeTruthy();
  });
});

describe("IntegrityCentre — a healthy repository", () => {
  it("runs the scan only once the button is activated", async () => {
    await render();
    await runCheck();

    expect(forRepository).toHaveBeenCalledTimes(1);
    expect(forRepository).toHaveBeenCalledWith("octocat", "demo");
  });

  it("states exactly what the server verified", async () => {
    await render();
    await runCheck();

    expect(text()).toContain("All 12 stored objects re-hashed to the ids they are filed under.");
  });

  it("renders the count the API sent rather than one of its own", async () => {
    forRepository.mockResolvedValue(report({ storedObjects: 3, verified: 3 }));

    await render();
    await runCheck();

    expect(text()).toContain("All 3 stored objects");
    expect(text()).not.toContain("12");
  });

  it("names the verdict in words, so it does not rest on colour", async () => {
    await render();
    await runCheck();

    expect(text()).toContain("Verified");
  });

  it("does not claim the repository is complete or correct", async () => {
    await render();
    await runCheck();

    expect(text()).toContain("does not establish that the history is complete");
  });

  it("says one object in the singular", async () => {
    forRepository.mockResolvedValue(report({ storedObjects: 1, verified: 1 }));

    await render();
    await runCheck();

    expect(text()).toContain("All 1 stored object re-hashed");
  });
});

describe("IntegrityCentre — a damaged repository", () => {
  const damagedReport = () =>
    report({
      healthy: false,
      damaged: [
        { id: DAMAGED_ID, reason: "HASH_MISMATCH", detail: "the stored bytes hash to a different id" },
        { id: SECOND_ID, reason: "UNREADABLE", detail: "the stored bytes could not be decompressed or parsed" },
      ],
    });

  it("reports how many failed out of how many were checked", async () => {
    forRepository.mockResolvedValue(damagedReport());

    await render();
    await runCheck();

    expect(text()).toContain("2 of 12 stored objects did not match");
  });

  it("names the verdict in words rather than only in colour", async () => {
    forRepository.mockResolvedValue(damagedReport());

    await render();
    await runCheck();

    expect(text()).toContain("Damaged");
  });

  it("shows every damaged object's full id", async () => {
    forRepository.mockResolvedValue(damagedReport());

    await render();
    await runCheck();

    expect(text()).toContain(DAMAGED_ID);
    expect(text()).toContain(SECOND_ID);
  });

  it("shows each classified reason and its detail", async () => {
    forRepository.mockResolvedValue(damagedReport());

    await render();
    await runCheck();

    expect(text()).toContain("hash mismatch");
    expect(text()).toContain("unreadable");
    expect(text()).toContain("the stored bytes hash to a different id");
  });

  it("falls back to the raw code for a reason it has not heard of", async () => {
    forRepository.mockResolvedValue(
      report({ healthy: false, damaged: [{ id: DAMAGED_ID, reason: "SOMETHING_NEW", detail: "" }] }),
    );

    await render();
    await runCheck();

    expect(text()).toContain("SOMETHING_NEW");
  });

  it("never claims verification succeeded", async () => {
    forRepository.mockResolvedValue(damagedReport());

    await render();
    await runCheck();

    expect(text()).not.toMatch(/re-hashed to the ids they are filed under/i);
  });
});

describe("IntegrityCentre — nothing verified", () => {
  it("does not present an empty repository as healthy", async () => {
    /* healthy is null when nothing was checked. Rendering that as a pass would
       be a claim the server explicitly declined to make. */
    forRepository.mockResolvedValue(
      report({ storedObjects: 0, verified: 0, healthy: null }),
    );

    await render();
    await runCheck();

    expect(text()).not.toContain("Verified");
    expect(text()).not.toMatch(/re-hashed/i);
    expect(text()).toContain("Nothing to verify");
  });

  it("shows the empty state without asking the server when there are no commits", async () => {
    repository.head = { branch: "main", commit: null };

    await render();

    expect(text()).toContain("no commits");
    expect(forRepository).not.toHaveBeenCalled();
    expect(runButton()).toBeFalsy();
  });
});

describe("IntegrityCentre — truncation", () => {
  it("says the result covers only part of the repository", async () => {
    forRepository.mockResolvedValue(
      report({ storedObjects: 40, verified: 25, truncated: true }),
    );

    await render();
    await runCheck();

    expect(text()).toContain("Only the first 25 of 40 stored objects were checked");
  });

  it("does not mention truncation when everything was checked", async () => {
    await render();
    await runCheck();

    expect(text()).not.toMatch(/Only the first/i);
  });
});

describe("IntegrityCentre — failure", () => {
  it("shows the error state when the request is rejected", async () => {
    forRepository.mockRejectedValue(
      Object.assign(new Error("Request failed"), {
        response: { status: 500, data: { message: "The repository could not be read" } },
      }),
    );

    await render();
    await runCheck();

    expect(text()).toContain("The check could not be run");
  });

  it("does not claim anything about health after a failure", async () => {
    forRepository.mockRejectedValue(new Error("network down"));

    await render();
    await runCheck();

    expect(text()).not.toContain("Verified");
    expect(text()).not.toMatch(/re-hashed/i);
  });

  it("offers the check again after a failure", async () => {
    forRepository.mockRejectedValue(new Error("network down"));

    await render();
    await runCheck();

    expect(text()).toContain("Run check again");
  });
});

describe("IntegrityCentre — no verification is claimed of the browser", () => {
  it("never suggests the page computed a hash itself", async () => {
    await render();
    await runCheck();

    expect(text()).not.toMatch(/verified locally|computed (the )?hash|checked in your browser/i);
  });
});
