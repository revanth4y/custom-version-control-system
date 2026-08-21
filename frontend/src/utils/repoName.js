/** Mirrors the server's rule in CreateRepoRequest. */
const NAME_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]*$/;

export const MAX_NAME_LENGTH = 100;
export const MAX_DESCRIPTION_LENGTH = 500;

/**
 * Checks a repository name before it is sent.
 *
 * The server validates this too and remains the authority; repeating the rule
 * here only turns a round trip into immediate feedback while typing. Client
 * validation is a convenience, never the gate — the server's own message is
 * shown verbatim if it disagrees.
 *
 * @returns a message describing the problem, or null when the name is usable
 */
export function validateRepositoryName(rawName) {
  const name = (rawName ?? "").trim();

  if (!name) return "A repository name is required.";
  if (name.length > MAX_NAME_LENGTH) return `Must be ${MAX_NAME_LENGTH} characters or fewer.`;
  if (!NAME_PATTERN.test(name)) {
    return "Use only letters, digits, '.', '_' or '-', starting with a letter or digit.";
  }
  return null;
}
