import { useCallback, useEffect, useState } from "react";

const STORAGE_KEY = "gitforge.colorMode";

/** What the user can choose. `system` follows the operating system. */
export const COLOR_MODES = ["system", "light", "dark"];

const isValid = (value) => COLOR_MODES.includes(value);

const stored = () => {
  try {
    const value = localStorage.getItem(STORAGE_KEY);
    return isValid(value) ? value : null;
  } catch {
    // Private browsing can refuse storage outright. A theme preference is not
    // worth an exception, so fall back to following the system.
    return null;
  }
};

const systemPrefersDark = () =>
  typeof window !== "undefined" &&
  typeof window.matchMedia === "function" &&
  window.matchMedia("(prefers-color-scheme: dark)").matches;

/** The scheme a mode actually resolves to right now. */
export const resolveScheme = (mode) => {
  if (mode === "light" || mode === "dark") return mode;
  return systemPrefersDark() ? "dark" : "light";
};

/**
 * The reader's colour preference.
 *
 * Defaults to `system`, which is the honest default: someone who has set their
 * operating system to light has already answered this question, and asking
 * again by defaulting to dark ignores that answer.
 *
 * The resolved scheme is mirrored onto `<html data-theme>` so `index.css` can
 * paint the page background before React mounts. Without that the first frame
 * is the wrong colour and the page visibly flips.
 */
export const useColorMode = () => {
  const [mode, setMode] = useState(() => stored() ?? "system");
  const [scheme, setScheme] = useState(() => resolveScheme(stored() ?? "system"));

  useEffect(() => {
    const resolved = resolveScheme(mode);
    setScheme(resolved);
    document.documentElement.setAttribute("data-theme", resolved);

    try {
      localStorage.setItem(STORAGE_KEY, mode);
    } catch {
      // Storage refused; the choice simply does not survive a reload.
    }
  }, [mode]);

  useEffect(() => {
    // Only while following the system does an OS change mean anything.
    if (mode !== "system") return undefined;
    if (typeof window.matchMedia !== "function") return undefined;

    const query = window.matchMedia("(prefers-color-scheme: dark)");
    const onChange = () => {
      const resolved = systemPrefersDark() ? "dark" : "light";
      setScheme(resolved);
      document.documentElement.setAttribute("data-theme", resolved);
    };

    query.addEventListener("change", onChange);
    return () => query.removeEventListener("change", onChange);
  }, [mode]);

  const cycle = useCallback(() => {
    setMode((current) => COLOR_MODES[(COLOR_MODES.indexOf(current) + 1) % COLOR_MODES.length]);
  }, []);

  return { mode, scheme, setMode, cycle };
};
