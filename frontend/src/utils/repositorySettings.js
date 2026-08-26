/**
 * The rules behind the repository settings form.
 *
 * Kept apart from the component because each one is a decision that can be
 * wrong in a way no amount of looking at the page would reveal: whether a field
 * counts as changed, and whether a typed name authorises a deletion.
 */

/**
 * Whether `next` differs from what the server holds.
 *
 * Trimmed on both sides, so trailing whitespace is not a change worth a
 * request. A description the server has never been given reads as empty rather
 * than as absent, which is what makes clearing one detectable at all.
 */
export function hasChanged(current, next) {
  return (current ?? "").trim() !== (next ?? "").trim();
}

/**
 * What to send for a description, or null when there is nothing to send.
 *
 * The server treats an absent field as "leave it alone" and an empty string as
 * "clear it". Those are different intentions and the form has to distinguish
 * them: omitting the field to clear a description silently does nothing.
 */
export function descriptionUpdate(current, next) {
  if (!hasChanged(current, next)) return null;
  return { description: (next ?? "").trim() };
}

/**
 * Whether a typed confirmation authorises deleting `name`.
 *
 * Exact, and deliberately not lenient: the whole purpose of typing the name is
 * that it cannot be done by accident, and matching case-insensitively or
 * ignoring a stray character would give away most of that. Surrounding
 * whitespace is forgiven because it comes from the clipboard rather than from
 * the person.
 */
export function confirmsDeletion(name, typed) {
  if (!name) return false;
  return (typed ?? "").trim() === name;
}

/**
 * The visibility choices, with what each one means for who can read the
 * repository. The wording is part of the control: "private" alone does not say
 * who else can still see it.
 */
export const VISIBILITY_OPTIONS = [
  { value: "PUBLIC", label: "Public", description: "Anyone can view this repository." },
  {
    value: "PRIVATE",
    label: "Private",
    description: "Only you and authorized users can view this repository.",
  },
];
