/**
 * Following one path through history.
 *
 * The path travels as a query parameter rather than another URL segment, and
 * that is the whole reason this file exists. Both a ref and a path may contain
 * slashes — `feature/login` is a branch, `src/main/App.java` is a file — so a
 * route of `commits/:ref/*` cannot say where one ends and the other begins.
 * `commits/feature/login/src/App.java` has no unambiguous reading. A query
 * parameter has exactly one.
 */

/**
 * A path as the API expects it: no surrounding whitespace, no leading or
 * trailing slashes.
 *
 * The server normalises too, and deliberately so — it cannot trust a caller.
 * Doing it here as well keeps the URL in the address bar tidy and makes the
 * comparison below meaningful, since `src` and `/src/` should not look like two
 * different filters to the interface.
 */
export const normalisePath = (path) => {
  if (typeof path !== "string") return "";
  return path.trim().replace(/^\/+/, "").replace(/\/+$/, "");
};

/** Whether a listing is narrowed to a path, or is the whole history. */
export const isFiltered = (path) => normalisePath(path).length > 0;

/**
 * Where the history of one path lives.
 *
 * A blank path is the repository root, whose history is the whole history, so
 * the parameter is left off entirely rather than sent empty — the two mean the
 * same thing to the server, and the shorter URL is the honest one.
 */
export const pathHistoryUrl = (owner, name, refName, path) => {
  const base = `/${owner}/${name}/commits/${encodeURIComponent(refName)}`;
  const target = normalisePath(path);
  return target ? `${base}?path=${encodeURIComponent(target)}` : base;
};

/**
 * Where a file can be read at a given revision.
 *
 * The ref is encoded because it is one segment; the path is not, because its
 * slashes are real separators the route is written to absorb.
 */
export const blobUrlAt = (owner, name, ref, path) =>
  `/${owner}/${name}/blob/${encodeURIComponent(ref)}/${normalisePath(path)}`;

/**
 * The last segment of a path, for labelling.
 *
 * A header reading "src/main/java/com/gitforge/App.java" wraps to three lines on
 * a narrow screen and buries the part that identifies the file. The full path
 * still belongs in a title attribute, where it costs nothing.
 */
export const basename = (path) => {
  const target = normalisePath(path);
  if (!target) return "";
  return target.slice(target.lastIndexOf("/") + 1);
};
