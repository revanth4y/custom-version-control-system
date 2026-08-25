/**
 * The syntax highlighter, loaded only when a file is actually opened.
 *
 * Everything here is behind a dynamic import, so the grammars form their own
 * chunk that the dashboard, the repository list and the overview never fetch.
 * Someone who never opens a file never downloads a highlighter.
 *
 * `highlight.js/lib/core` carries no grammars of its own; each is registered
 * explicitly from the closed set in `language.js`. That is the whole reason the
 * cost is bounded — the default `highlight.js` entry point pulls in every
 * grammar it ships, which is far larger than this application.
 */

let enginePromise = null;

/**
 * The engine, with the curated grammars registered.
 *
 * Cached as a promise rather than a value so that two files opened in quick
 * succession share one download rather than racing to start two.
 */
const engine = () => {
  if (enginePromise) return enginePromise;

  enginePromise = (async () => {
    const [{ default: hljs }, ...grammars] = await Promise.all([
      import("highlight.js/lib/core"),
      import("highlight.js/lib/languages/java"),
      import("highlight.js/lib/languages/javascript"),
      import("highlight.js/lib/languages/typescript"),
      import("highlight.js/lib/languages/python"),
      import("highlight.js/lib/languages/xml"),
      import("highlight.js/lib/languages/css"),
      import("highlight.js/lib/languages/c"),
      import("highlight.js/lib/languages/cpp"),
      import("highlight.js/lib/languages/csharp"),
      import("highlight.js/lib/languages/go"),
      import("highlight.js/lib/languages/rust"),
      import("highlight.js/lib/languages/php"),
      import("highlight.js/lib/languages/ruby"),
      import("highlight.js/lib/languages/bash"),
      import("highlight.js/lib/languages/sql"),
      import("highlight.js/lib/languages/yaml"),
      import("highlight.js/lib/languages/json"),
      import("highlight.js/lib/languages/markdown"),
      import("highlight.js/lib/languages/kotlin"),
      import("highlight.js/lib/languages/swift"),
      import("highlight.js/lib/languages/dockerfile"),
      import("highlight.js/lib/languages/diff"),
    ]);

    const ids = [
      "java", "javascript", "typescript", "python", "xml", "css", "c", "cpp",
      "csharp", "go", "rust", "php", "ruby", "bash", "sql", "yaml", "json",
      "markdown", "kotlin", "swift", "dockerfile", "diff",
    ];

    ids.forEach((id, index) => hljs.registerLanguage(id, grammars[index].default));

    return hljs;
  })();

  return enginePromise;
};

/**
 * Highlight one line, returning HTML.
 *
 * Per line rather than per file, because the viewer renders a table row per
 * line and needs the numbering to stay aligned with the source. `ignoreIllegals`
 * matters for exactly that reason: a single line lifted out of its file is
 * frequently not valid on its own — an open brace, half a string — and without
 * it the grammar would throw on ordinary code.
 *
 * The text is never altered. highlight.js escapes what it emits and wraps
 * spans around it, so what the reader sees is the file's own bytes.
 */
export const highlightLine = (hljs, line, language) => {
  if (!hljs || !language || !line) return null;

  try {
    return hljs.highlight(line, { language, ignoreIllegals: true }).value;
  } catch {
    /* An unregistered grammar should be impossible, but a highlighter is a
       convenience: if it cannot colour a line, the line is still readable. */
    return null;
  }
};

export default engine;
