/**
 * Reading the repository's loading state, so callers can tell three situations
 * apart that otherwise look alike.
 *
 * A repository page has to distinguish "HEAD has not arrived yet" from "HEAD
 * arrived and there are no commits" from "HEAD arrived and here is the commit".
 * All three present as a falsy `head.commit` if you only check for truthiness,
 * which is how the file listing came to request a tree for a repository that
 * has none — the server answers 404, correctly, because without a commit there
 * is no directory to list.
 *
 * These are plain functions rather than hook logic so the rules can be tested
 * directly; getting them wrong shows up as a spurious request rather than a
 * visible fault, which is exactly the kind of thing that hides.
 */

/** HEAD has been fetched and answered. `null` means the request is still out. */
export const headResolved = (head) => head != null;

/**
 * The repository has no commits at all.
 *
 * Only meaningful once HEAD has resolved: before that the honest answer is "not
 * known yet", and treating unknown as empty would render the empty state to
 * someone whose repository is merely still loading.
 */
export const isEmptyRepository = (head) => headResolved(head) && !head.commit;

/**
 * Whether the file listing should be requested at all.
 *
 * False while HEAD is in flight, and false for a repository with no commits.
 * The second case is the one that mattered: the answer is already known from
 * HEAD, so asking produces a 404 that tells us nothing we did not have.
 */
export const shouldLoadTree = (head) => headResolved(head) && Boolean(head.commit);

/**
 * Identity of the repository currently being viewed.
 *
 * Used as a React key so that moving between repositories remounts rather than
 * re-renders. Without it the provider keeps serving the previous repository's
 * data while its children re-render against the new owner and name — which is
 * how requests for one repository came to be issued under another's identity,
 * and how a missing repository could leave the previous page on screen.
 */
export const repositoryKey = (owner, name) => `${owner ?? ""}/${name ?? ""}`;
