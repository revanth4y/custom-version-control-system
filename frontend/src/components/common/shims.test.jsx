import { createRef } from "react";
import { createRoot } from "react-dom/client";
import { act } from "react-dom/test-utils";
import { MemoryRouter } from "react-router-dom";
import { RepoIcon } from "@primer/octicons-react";
import { afterEach, describe, expect, it, vi } from "vitest";

/* Importing anything from @primer/react loads its PageLayout module, which asks
   `CSS.supports` whether the browser understands dvh units. jsdom has no CSS
   global at all, so the import throws before a single test runs. `vi.hoisted`
   is the one hook that executes ahead of module evaluation, which is where this
   has to happen. Answering "no" is correct for jsdom either way. */
vi.hoisted(() => {
  globalThis.CSS = globalThis.CSS ?? { supports: () => false };

  /* React 18 only treats `act` as real when this flag is set; without it every
     render logs a warning about the environment not supporting act. */
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
});

import RouterLink from "./RouterLink";
import Octicon from "./Octicon";

/**
 * The two shims exist for one reason: Primer compiles `sx` into a class and then
 * forwards the prop onward, and the component underneath spreads it onto its
 * element. The result is a literal `sx="[object Object]"` attribute on links and
 * icons throughout the application — invalid HTML that nothing warns about,
 * because a lowercase attribute is legal as far as React is concerned.
 *
 * These tests pin both halves of the contract. Dropping `sx` is only half of it:
 * a shim that also dropped `to`, `className` or a ref would break navigation and
 * styling while still passing an "sx is gone" assertion.
 *
 * Rendered with react-dom directly rather than a testing library, because the
 * project has no rendering-library dependency and this fix is not the place to
 * introduce one.
 */

let host = null;
let root = null;

const mount = (ui) => {
  host = document.createElement("div");
  document.body.appendChild(host);
  root = createRoot(host);
  act(() => root.render(ui));
  return host;
};

afterEach(() => {
  if (root) act(() => root.unmount());
  if (host) host.remove();
  root = null;
  host = null;
});

describe("RouterLink shim", () => {
  it("does not write sx onto the anchor", () => {
    const el = mount(
      <MemoryRouter>
        <RouterLink to="/somewhere" sx={{ color: "red" }}>go</RouterLink>
      </MemoryRouter>,
    );

    const anchor = el.querySelector("a");
    expect(anchor).not.toBeNull();
    expect(anchor.hasAttribute("sx")).toBe(false);
  });

  it("still navigates — `to` becomes a real href", () => {
    const el = mount(
      <MemoryRouter>
        <RouterLink to="/owner/repo" sx={{ color: "red" }}>go</RouterLink>
      </MemoryRouter>,
    );

    expect(el.querySelector("a").getAttribute("href")).toBe("/owner/repo");
  });

  it("forwards the class Primer generated, so styling survives", () => {
    const el = mount(
      <MemoryRouter>
        <RouterLink to="/x" sx={{ color: "red" }} className="Box-sc-generated">go</RouterLink>
      </MemoryRouter>,
    );

    expect(el.querySelector("a").getAttribute("class")).toBe("Box-sc-generated");
  });

  it("forwards everything else it is given", () => {
    const el = mount(
      <MemoryRouter>
        <RouterLink to="/x" aria-label="Open" title="tip" data-probe="yes" sx={{}}>go</RouterLink>
      </MemoryRouter>,
    );

    const anchor = el.querySelector("a");
    expect(anchor.getAttribute("aria-label")).toBe("Open");
    expect(anchor.getAttribute("title")).toBe("tip");
    expect(anchor.getAttribute("data-probe")).toBe("yes");
    expect(anchor.textContent).toBe("go");
  });

  it("forwards a ref, which overlays and menus anchor themselves to", () => {
    const ref = createRef();
    mount(
      <MemoryRouter>
        <RouterLink to="/x" ref={ref}>go</RouterLink>
      </MemoryRouter>,
    );

    expect(ref.current).not.toBeNull();
    expect(ref.current.tagName).toBe("A");
  });

  it("is unbothered by having no sx at all", () => {
    const el = mount(
      <MemoryRouter>
        <RouterLink to="/x">go</RouterLink>
      </MemoryRouter>,
    );

    const anchor = el.querySelector("a");
    expect(anchor.hasAttribute("sx")).toBe(false);
    expect(anchor.getAttribute("href")).toBe("/x");
  });
});

describe("Octicon shim", () => {
  it("does not write sx onto the svg", () => {
    const el = mount(<Octicon icon={RepoIcon} sx={{ color: "red" }} />);

    const svg = el.querySelector("svg");
    expect(svg).not.toBeNull();
    expect(svg.hasAttribute("sx")).toBe(false);
  });

  it("still renders the icon it was asked for", () => {
    const el = mount(<Octicon icon={RepoIcon} sx={{ color: "red" }} />);

    expect(el.querySelector("svg").getAttribute("class")).toContain("octicon-repo");
  });

  it("keeps size, which belongs to the icon rather than to sx", () => {
    const el = mount(<Octicon icon={RepoIcon} size={24} sx={{ color: "red" }} />);

    const svg = el.querySelector("svg");
    expect(svg.getAttribute("width")).toBe("24");
    expect(svg.getAttribute("height")).toBe("24");
  });

  it("still lets Primer style it, which is why the strip happens below Primer", () => {
    const el = mount(<Octicon icon={RepoIcon} sx={{ color: "rgb(1, 2, 3)" }} />);

    /* Primer still receives sx and still emits a class for it; only the
       redundant attribute is dropped further down. */
    expect(el.querySelector("svg").getAttribute("class")).toMatch(/Octicon|octicon/);
  });

  it("is unbothered by having no sx at all", () => {
    const el = mount(<Octicon icon={RepoIcon} />);

    expect(el.querySelector("svg").hasAttribute("sx")).toBe(false);
  });

  it("reuses one wrapper per icon, so React updates rather than remounts", () => {
    const el = mount(<Octicon icon={RepoIcon} size={16} />);
    const first = el.querySelector("svg");

    act(() => root.render(<Octicon icon={RepoIcon} size={24} />));

    /* A wrapper rebuilt on every render would be a new component type, and React
       would unmount the old svg and mount a fresh one in its place. */
    expect(el.querySelector("svg")).toBe(first);
    expect(first.getAttribute("width")).toBe("24");
  });
});
