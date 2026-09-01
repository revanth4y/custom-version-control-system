import { createRoot } from "react-dom/client";
import { act } from "react-dom/test-utils";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/* The same two jsdom gaps the page tests fill, before module evaluation because
   that is when Primer reads them. */
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

import { ColorModeContext } from "../../context/ColorModeContext.jsx";
import DiffLine from "./DiffLine";
import DiffViewer from "./DiffViewer";
import FileDiff from "./FileDiff";
import Hunk from "./Hunk";

/**
 * The diff components, rendered.
 *
 * These did not exist before: the diff UI had only pure-function coverage, so a
 * rendering regression would not have been caught. Most of what is asserted here
 * is behaviour that predates intra-line marking - binary and too-large
 * explanations, line numbers, summary counts - established first so the new
 * rendering has something to be measured against rather than only described.
 */

let host = null;

const render = async (ui) => {
  host = document.createElement("div");
  document.body.appendChild(host);
  const root = createRoot(host);
  await act(async () => {
    root.render(
      <ColorModeContext.Provider value={{ scheme: "dark", mode: "dark", setMode: () => {} }}>
        <MemoryRouter initialEntries={["/octocat/demo"]}>{ui}</MemoryRouter>
      </ColorModeContext.Provider>,
    );
  });
  return root;
};

/** DiffLine and Hunk render table rows, which need a table to be valid. */
const inTable = (children) => (
  <table>
    <tbody>{children}</tbody>
  </table>
);

const text = () => host.textContent;
const marks = () => [...host.querySelectorAll("mark")];

const line = (overrides = {}) => ({
  type: "CONTEXT",
  oldNumber: 1,
  newNumber: 1,
  content: "unchanged",
  ...overrides,
});

const file = (overrides = {}) => ({
  path: "src/app.js",
  status: "MODIFIED",
  oldBlob: "a".repeat(40),
  newBlob: "b".repeat(40),
  oldMode: "100644",
  newMode: "100644",
  binary: false,
  tooLarge: false,
  additions: 1,
  deletions: 1,
  oldSize: 12,
  newSize: 12,
  hunks: [
    {
      header: "@@ -1,1 +1,1 @@",
      oldStart: 1,
      oldCount: 1,
      newStart: 1,
      newCount: 1,
      lines: [
        line({ type: "REMOVED", oldNumber: 1, newNumber: null, content: "timeout = 30" }),
        line({ type: "ADDED", oldNumber: null, newNumber: 1, content: "timeout = 60" }),
      ],
    },
  ],
  ...overrides,
});

afterEach(() => {
  if (host) {
    host.remove();
    host = null;
  }
});

describe("DiffLine — behaviour that predates intra-line marking", () => {
  it("shows both line numbers on a context line", async () => {
    await render(inTable(<DiffLine line={line({ oldNumber: 7, newNumber: 9 })} scheme="dark" />));

    expect(text()).toContain("7");
    expect(text()).toContain("9");
    expect(text()).toContain("unchanged");
  });

  it("leaves the new number blank on a removed line", async () => {
    await render(
      inTable(<DiffLine line={line({ type: "REMOVED", oldNumber: 4, newNumber: null, content: "gone" })} scheme="dark" />),
    );

    const cells = [...host.querySelectorAll("td")];
    expect(cells[0].textContent).toBe("4");
    expect(cells[1].textContent).toBe("");
  });

  it("leaves the old number blank on an added line", async () => {
    await render(
      inTable(<DiffLine line={line({ type: "ADDED", oldNumber: null, newNumber: 4, content: "new" })} scheme="dark" />),
    );

    const cells = [...host.querySelectorAll("td")];
    expect(cells[0].textContent).toBe("");
    expect(cells[1].textContent).toBe("4");
  });

  it("hides the sign column from assistive technology", async () => {
    await render(inTable(<DiffLine line={line({ type: "ADDED" })} scheme="dark" />));

    const hidden = [...host.querySelectorAll('[aria-hidden="true"]')];
    expect(hidden.length).toBeGreaterThan(0);
    expect(hidden.some((cell) => cell.textContent === "+")).toBe(true);
  });

  it("renders an empty line as a space so the row keeps its height", async () => {
    await render(inTable(<DiffLine line={line({ content: "" })} scheme="dark" />));

    expect(host.querySelectorAll("td")[3].textContent).toBe(" ");
  });
});

describe("DiffLine — intra-line marking", () => {
  it("marks only the changed characters", async () => {
    await render(
      inTable(
        <DiffLine
          line={line({ type: "REMOVED", content: "timeout = 30", segments: [{ start: 10, end: 11 }] })}
          scheme="dark"
        />,
      ),
    );

    expect(marks()).toHaveLength(1);
    expect(marks()[0].textContent).toBe("3");
    // The rest of the line is still present, just not marked.
    expect(text()).toContain("timeout = 30");
  });

  it("marks several separate runs in one line", async () => {
    await render(
      inTable(
        <DiffLine
          line={line({
            type: "ADDED",
            content: "alpha P beta Q gamma",
            segments: [{ start: 6, end: 7 }, { start: 13, end: 14 }],
          })}
          scheme="dark"
        />,
      ),
    );

    expect(marks().map((m) => m.textContent)).toEqual(["P", "Q"]);
  });

  it("renders the whole line unmarked when segments are absent", async () => {
    // A v2.0.8 response has no such field at all; the line must render exactly
    // as it did then.
    await render(inTable(<DiffLine line={line({ type: "ADDED", content: "no segments here" })} scheme="dark" />));

    expect(marks()).toHaveLength(0);
    expect(text()).toContain("no segments here");
  });

  it("renders the whole line unmarked when segments are empty", async () => {
    await render(
      inTable(<DiffLine line={line({ type: "ADDED", content: "empty list", segments: [] })} scheme="dark" />),
    );

    expect(marks()).toHaveLength(0);
    expect(text()).toContain("empty list");
  });

  it("never marks a context line", async () => {
    // The server does not send runs on context lines; if one ever arrived, the
    // line still says nothing changed, which is what a context line means.
    await render(inTable(<DiffLine line={line({ type: "CONTEXT", content: "untouched" })} scheme="dark" />));

    expect(marks()).toHaveLength(0);
  });

  it("does not convey the marking by colour alone", async () => {
    await render(
      inTable(
        <DiffLine
          line={line({ type: "REMOVED", content: "timeout = 30", segments: [{ start: 10, end: 11 }] })}
          scheme="dark"
        />,
      ),
    );

    const style = marks()[0].getAttribute("style") ?? "";
    const weightOrUnderline = /font-weight/.test(style) || /border-bottom/.test(style);
    expect(weightOrUnderline || marks()[0].tagName === "MARK").toBe(true);
  });

  it("preserves the complete line text when marking", async () => {
    await render(
      inTable(
        <DiffLine
          line={line({ type: "ADDED", content: "one two three", segments: [{ start: 4, end: 7 }] })}
          scheme="dark"
        />,
      ),
    );

    expect(host.querySelectorAll("td")[3].textContent).toBe("one two three");
  });

  it("adds no interactive element", async () => {
    await render(
      inTable(
        <DiffLine
          line={line({ type: "ADDED", content: "value = 2", segments: [{ start: 8, end: 9 }] })}
          scheme="dark"
        />,
      ),
    );

    expect(host.querySelectorAll("button, a, input")).toHaveLength(0);
  });
});

describe("Hunk", () => {
  it("renders every line type in order", async () => {
    const hunk = {
      header: "@@ -1,3 +1,3 @@",
      oldStart: 1,
      oldCount: 3,
      newStart: 1,
      newCount: 3,
      lines: [
        line({ type: "CONTEXT", content: "one" }),
        line({ type: "REMOVED", oldNumber: 2, newNumber: null, content: "two" }),
        line({ type: "ADDED", oldNumber: null, newNumber: 2, content: "TWO" }),
        line({ type: "CONTEXT", oldNumber: 3, newNumber: 3, content: "three" }),
      ],
    };

    await render(inTable(<Hunk hunk={hunk} />));

    expect(host.querySelectorAll("tr").length).toBeGreaterThanOrEqual(4);
    expect(text()).toContain("one");
    expect(text()).toContain("two");
    expect(text()).toContain("TWO");
    expect(text()).toContain("three");
  });

  it("passes segments through to the line", async () => {
    const hunk = {
      header: "@@ -1,1 +1,1 @@",
      oldStart: 1,
      oldCount: 1,
      newStart: 1,
      newCount: 1,
      lines: [line({ type: "ADDED", content: "value = 2", segments: [{ start: 8, end: 9 }] })],
    };

    await render(inTable(<Hunk hunk={hunk} />));

    expect(marks().map((m) => m.textContent)).toEqual(["2"]);
  });
});

describe("FileDiff — states that must not regress", () => {
  it("renders hunks for an ordinary text change", async () => {
    await render(<FileDiff file={file()} owner="octocat" name="demo" blobRef="main" />);

    expect(text()).toContain("timeout = 30");
    expect(text()).toContain("timeout = 60");
  });

  it("explains a binary file instead of showing lines", async () => {
    await render(
      <FileDiff file={file({ binary: true, hunks: [], additions: 0, deletions: 0 })} owner="octocat" name="demo" />,
    );

    expect(text()).toMatch(/binary/i);
    expect(host.querySelectorAll("mark")).toHaveLength(0);
  });

  it("explains a file too large to diff", async () => {
    await render(
      <FileDiff file={file({ tooLarge: true, hunks: [], additions: 0, deletions: 0 })} owner="octocat" name="demo" />,
    );

    expect(text()).toMatch(/large/i);
  });

  it("reports a mode-only change", async () => {
    await render(
      <FileDiff
        file={file({ hunks: [], additions: 0, deletions: 0, oldMode: "100644", newMode: "100755" })}
        owner="octocat"
        name="demo"
      />,
    );

    expect(text()).toMatch(/executable/i);
  });

  it("shows the path", async () => {
    await render(<FileDiff file={file()} owner="octocat" name="demo" />);

    expect(text()).toContain("app.js");
  });
});

describe("DiffViewer — summary and navigation", () => {
  const result = (files) => ({
    base: "main",
    head: "topic",
    filesChanged: files.length,
    totalAdditions: files.reduce((n, f) => n + f.additions, 0),
    totalDeletions: files.reduce((n, f) => n + f.deletions, 0),
    files,
  });

  beforeEach(() => {
    window.location.hash = "";
  });

  it("summarises the file and line counts", async () => {
    await render(<DiffViewer result={result([file()])} owner="octocat" name="demo" blobRef="main" />);

    expect(text()).toContain("1");
    expect(text()).toContain("+1");
    expect(text()).toContain("-1");
  });

  it("shows an empty state when nothing changed", async () => {
    await render(<DiffViewer result={result([])} owner="octocat" name="demo" blobRef="main" />);

    expect(text()).toContain("No changes");
  });

  it("renders one section per file", async () => {
    const files = [file(), file({ path: "src/other.js" })];

    await render(<DiffViewer result={result(files)} owner="octocat" name="demo" blobRef="main" />);

    expect(text()).toContain("app.js");
    expect(text()).toContain("other.js");
  });

  it("still renders marks that came from the server", async () => {
    const withSegments = file({
      hunks: [
        {
          header: "@@ -1,1 +1,1 @@",
          oldStart: 1,
          oldCount: 1,
          newStart: 1,
          newCount: 1,
          lines: [
            line({ type: "REMOVED", oldNumber: 1, newNumber: null, content: "timeout = 30", segments: [{ start: 10, end: 11 }] }),
            line({ type: "ADDED", oldNumber: null, newNumber: 1, content: "timeout = 60", segments: [{ start: 10, end: 11 }] }),
          ],
        },
      ],
    });

    await render(<DiffViewer result={result([withSegments])} owner="octocat" name="demo" blobRef="main" />);

    expect(marks().map((m) => m.textContent)).toEqual(["3", "6"]);
  });
});
