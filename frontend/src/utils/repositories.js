/**
 * Reading a repository record for display.
 *
 * The API returns exactly `id, name, description, visibility, ownerUsername,
 * createdAt, updatedAt` — no language, stars, forks or watchers. Everything
 * shown about a repository therefore comes from these functions, and anything
 * they cannot answer is not displayed rather than guessed at.
 *
 * Kept out of the components so the rules can be tested without rendering.
 */

/** Where a repository card links to. */
export const repositoryPath = (repo) => `/${repo.ownerUsername}/${repo.name}`;

export const isPrivate = (repo) => repo?.visibility === "PRIVATE";

/**
 * The badge text.
 *
 * Both states are labelled rather than only the private one. A card with no
 * badge is ambiguous — it reads as "public" to someone who knows the
 * convention and as "unknown" to everyone else — and the reference shows a
 * badge on every card.
 */
export const visibilityLabel = (repo) => (isPrivate(repo) ? "Private" : "Public");

/**
 * "7 repositories", and the one case where that would read wrongly.
 *
 * `undefined` is not zero: it means the count has not arrived, and saying
 * "0 repositories" while loading states something false.
 */
export const repositoryCountLabel = (count) => {
  if (count === undefined || count === null) return "counting repositories";
  return `${count} ${count === 1 ? "repository" : "repositories"}`;
};

/**
 * The description, or nothing.
 *
 * The column is nullable and a repository created without one comes back with
 * `null`; a whitespace-only description is equally absent as far as a reader is
 * concerned, and would otherwise reserve a line of empty space.
 */
export const descriptionOf = (repo) => {
  const description = (repo?.description ?? "").trim();
  return description || null;
};
