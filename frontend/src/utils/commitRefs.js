/**
 * Which refs point at which commits.
 *
 * A branch is a name for one commit id, and that is the whole of the
 * relationship. The association is therefore a lookup by id and nothing more -
 * no inference from position, from order, or from which commits happen to be
 * loaded. A commit with no ref pointing at it simply has none.
 *
 * Only what the backend reported is ever shown. `GET /branches` gives the name
 * and the commit each branch points at; `GET /head` gives the branch HEAD is
 * on. Anything beyond that would be decoration invented in the browser.
 */

/**
 * Builds a sha to refs index.
 *
 * Several branches may point at the same commit - that is ordinary, not a
 * conflict - so each entry is a list. Names are sorted so the same repository
 * labels a node the same way twice running; a listing does not promise an
 * order, and a graph that reorders its own labels between renders looks like it
 * is telling you something when it is not.
 *
 * @param branches the payload of GET /branches
 * @param head the payload of GET /head, or null
 * @returns Map of commit sha to an array of { name, isHead }
 */
export function indexRefs(branches, head) {
  const index = new Map();
  const headBranch = head?.branch ?? null;

  for (const branch of branches ?? []) {
    if (!branch?.name || !branch?.commit) {
      // A branch without a tip is an unborn branch: it names no commit, so
      // there is no node to attach it to.
      continue;
    }
    const existing = index.get(branch.commit) ?? [];
    existing.push({ name: branch.name, isHead: branch.name === headBranch });
    index.set(branch.commit, existing);
  }

  for (const [sha, refs] of index) {
    index.set(
      sha,
      [...refs].sort((a, b) => a.name.localeCompare(b.name)),
    );
  }
  return index;
}

/** The refs pointing at one commit, newest-first order irrelevant - always sorted. */
export function refsFor(index, sha) {
  return index?.get(sha) ?? [];
}

/**
 * How a commit reads to someone who cannot see the graph.
 *
 * The visual encodes several things at once - a hollow ring for a merge, a
 * stub that fades for a parent not yet loaded, a lane colour that persists -
 * and none of that survives being described as "a circle". What actually
 * matters is stated in words instead: what the commit is, what it points back
 * to, and what points at it.
 *
 * Parents are named by their abbreviation because that is what the interface
 * shows everywhere else, and because forty characters read aloud is not
 * information, it is an obstacle.
 *
 * @param node a row from buildCommitGraph
 * @param refs the refs pointing at this commit
 */
export function describeNode(node, refs = []) {
  if (!node) {
    return "";
  }
  const parts = [`Commit ${node.shortSha}`];

  if (node.isMerge) {
    parts.push(`merge of ${node.parents.length} parents`);
  } else if (node.isRoot) {
    parts.push("root commit, no parents");
  }

  if (refs.length > 0) {
    parts.push(refs.map((ref) => (ref.isHead ? `${ref.name} (HEAD)` : ref.name)).join(", "));
  }

  const firstLine = (node.commit?.message ?? "").split("\n")[0].trim();
  if (firstLine) {
    parts.push(firstLine);
  }

  if (node.parents.length > 0 && !node.isMerge) {
    parts.push(`parent ${abbreviate(node.parents[0])}`);
  } else if (node.isMerge) {
    parts.push(`parents ${node.parents.map(abbreviate).join(", ")}`);
  }

  if (node.boundaryParents.length > 0) {
    // Said plainly, because the visual says it with a fading line that assistive
    // technology has no way to convey.
    parts.push(
      node.boundaryParents.length === 1
        ? "1 parent not loaded yet"
        : `${node.boundaryParents.length} parents not loaded yet`,
    );
  }

  return parts.join(". ");
}

/** The first seven characters, as the interface abbreviates ids everywhere else. */
export function abbreviate(sha) {
  return typeof sha === "string" ? sha.slice(0, 7) : "";
}
