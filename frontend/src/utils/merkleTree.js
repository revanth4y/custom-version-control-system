/**
 * Walking the object graph by path, and describing what is found there.
 *
 * The engine is a Merkle tree: a tree entry stores a child's id, and that id is
 * a hash of the child's own contents, so a tree's hash transitively covers
 * everything beneath it. This module holds the arithmetic of moving through that
 * structure - which path leads where, what an object's ancestry is, and how to
 * say in words what the listing shows.
 *
 * Deliberately separate from the commit graph. A commit DAG and a Merkle tree
 * are different shapes: one has multiple parents and needs lanes and a
 * topological order, the other is a strict tree addressed by path. Sharing a
 * model between them would mean bending one to fit the other.
 *
 * <strong>Nothing here computes or checks a hash.</strong> Every id shown comes
 * from the server, and the framed bytes an id is taken over are not exposed by
 * any endpoint, so the browser is in no position to verify anything. Saying it
 * did would be a claim this code cannot support.
 */

/** The last segment of a path, which is what a tree entry is named. */
export function basename(path) {
  const segments = normalise(path).split("/").filter(Boolean);
  return segments.length === 0 ? "" : segments[segments.length - 1];
}

/** The directory containing a path, or the empty string at the root. */
export function parentPath(path) {
  const segments = normalise(path).split("/").filter(Boolean);
  return segments.slice(0, -1).join("/");
}

/** No surrounding whitespace, no leading or trailing slashes. */
export function normalise(path) {
  return typeof path === "string" ? path.trim().replace(/^\/+|\/+$/g, "") : "";
}

/**
 * The chain from the repository root down to a path.
 *
 * Each step carries the path that reaches it, so a breadcrumb can link every
 * level without recomputing prefixes. The root itself is not included: it is the
 * commit's tree, which the page shows separately as the start of the chain.
 */
export function ancestry(path) {
  const segments = normalise(path).split("/").filter(Boolean);
  return segments.map((name, index) => ({
    name,
    path: segments.slice(0, index + 1).join("/"),
  }));
}

/** The explorer's URL for one object. */
export function merkleUrl(owner, name, refName, path) {
  const base = `/${owner}/${name}/merkle/${encodeURIComponent(refName)}`;
  const target = normalise(path);
  return target ? `${base}?path=${encodeURIComponent(target)}` : base;
}

/**
 * What kind of object an entry names, in words.
 *
 * Read from the type and mode the server sent, never guessed from the name. An
 * executable is a distinct mode in the stored tree, so it is worth saying rather
 * than flattening into "file" - the mode is part of what the tree hashes.
 */
export function kindOf(entry) {
  if (!entry) {
    return "";
  }
  if (entry.type === "dir") {
    return "tree";
  }
  return entry.mode === "100755" ? "executable file" : "file";
}

/** The first seven characters, as ids are abbreviated everywhere else. */
export function abbreviate(id) {
  return typeof id === "string" ? id.slice(0, 7) : "";
}

/**
 * How one entry in a listing reads to someone who cannot see it.
 *
 * The row shows a name, an icon and a short id in separate columns; read aloud
 * that is three fragments with no relationship between them. The accessible name
 * states what the entry is, what it is called, and the id the parent tree
 * recorded for it - which is the fact the whole view exists to show.
 */
export function describeEntry(entry) {
  if (!entry) {
    return "";
  }
  const kind = kindOf(entry);
  const action = entry.type === "dir" ? "Open" : "Inspect";
  return `${action} ${kind} ${entry.name}, object ${abbreviate(entry.id)}`;
}

/**
 * Finds an entry by its full path within a listing.
 *
 * The listing gives each entry a full path, so this is a lookup rather than a
 * join. It is how the explorer learns a directory's own id: a tree does not
 * report its own hash, but its parent records it, which is exactly the Merkle
 * relationship being displayed.
 */
export function entryAt(entries, path) {
  const target = normalise(path);
  return (entries ?? []).find((entry) => normalise(entry.path) === target) ?? null;
}
