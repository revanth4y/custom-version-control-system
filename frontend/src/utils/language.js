/**
 * Which grammar, if any, a file should be highlighted with.
 *
 * The set is deliberately closed. highlight.js ships nearly two hundred
 * grammars and bundling them all would cost more than the rest of the
 * application put together, so this covers the languages GitForge already
 * claims to know — the same list the palette colours repository cards by — and
 * anything outside it is shown as plain text rather than guessed at.
 *
 * Returning `null` is a normal answer, not a failure: an unknown extension, a
 * file with no extension, and a binary file all mean "no grammar", and the
 * viewer renders the source unchanged.
 */

/** Extension (lower case, no dot) to highlight.js language id. */
const BY_EXTENSION = {
  java: "java",
  js: "javascript",
  jsx: "javascript",
  mjs: "javascript",
  cjs: "javascript",
  ts: "typescript",
  tsx: "typescript",
  py: "python",
  html: "xml",
  htm: "xml",
  xml: "xml",
  svg: "xml",
  vue: "xml",
  css: "css",
  scss: "css",
  c: "c",
  h: "c",
  cpp: "cpp",
  cc: "cpp",
  cxx: "cpp",
  hpp: "cpp",
  cs: "csharp",
  go: "go",
  rs: "rust",
  php: "php",
  rb: "ruby",
  sh: "bash",
  bash: "bash",
  zsh: "bash",
  sql: "sql",
  yml: "yaml",
  yaml: "yaml",
  json: "json",
  md: "markdown",
  markdown: "markdown",
  kt: "kotlin",
  kts: "kotlin",
  swift: "swift",
  diff: "diff",
  patch: "diff",
};

/** Files whose name, not extension, decides the grammar. */
const BY_FILENAME = {
  dockerfile: "dockerfile",
  makefile: "plaintext",
  license: "plaintext",
};

/** Every grammar this build can load. Nothing outside it is fetched. */
export const SUPPORTED_LANGUAGES = [...new Set(Object.values(BY_EXTENSION))]
  .concat("dockerfile")
  .filter((id) => id !== "plaintext")
  .sort();

/**
 * The grammar for a path, or null when there is no sensible one.
 *
 * The filename is checked before the extension so that `Dockerfile` and
 * `Dockerfile.dev` both resolve, and a bare `LICENSE` is not mistaken for a
 * file with no extension worth guessing at.
 */
export const languageFor = (path, { binary = false } = {}) => {
  if (binary) return null;

  const file = (path ?? "").split("/").pop()?.toLowerCase() ?? "";
  if (!file) return null;

  const base = file.split(".")[0];
  const named = BY_FILENAME[base];
  if (named) return named === "plaintext" ? null : named;

  const parts = file.split(".");
  if (parts.length < 2) return null;

  return BY_EXTENSION[parts.pop()] ?? null;
};
