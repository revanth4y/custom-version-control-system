/** Presentation helpers shared by the Insights tabs. */

/** A count, grouped, or a dash when the API did not report one. */
export const count = (value) =>
  value === null || value === undefined ? "—" : Number(value).toLocaleString();

/**
 * A duration in words, coarsened to the largest unit that still says something.
 *
 * Deliberately approximate above a day: "1 year, 2 months" is what a reader
 * wants from a repository age, and the exact second is available in the
 * timestamps the same tab shows.
 */
export function humanDuration(seconds) {
  if (seconds === null || seconds === undefined) return "—";
  const total = Math.max(0, Math.floor(seconds));
  if (total < 60) return `${total} ${total === 1 ? "second" : "seconds"}`;

  const minutes = Math.floor(total / 60);
  if (minutes < 60) return `${minutes} ${minutes === 1 ? "minute" : "minutes"}`;

  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} ${hours === 1 ? "hour" : "hours"}`;

  const days = Math.floor(hours / 24);
  if (days < 31) return `${days} ${days === 1 ? "day" : "days"}`;

  const months = Math.floor(days / 30);
  if (months < 12) return `${months} ${months === 1 ? "month" : "months"}`;

  const years = Math.floor(days / 365);
  const remainingMonths = Math.floor((days - years * 365) / 30);
  const yearPart = `${years} ${years === 1 ? "year" : "years"}`;
  return remainingMonths === 0
    ? yearPart
    : `${yearPart}, ${remainingMonths} ${remainingMonths === 1 ? "month" : "months"}`;
}

/** Whole days between two instants, inclusive of both ends. */
export function daysBetween(fromIso, toIso) {
  if (!fromIso || !toIso) return null;
  const from = Date.parse(fromIso);
  const to = Date.parse(toIso);
  if (Number.isNaN(from) || Number.isNaN(to)) return null;
  return Math.floor((to - from) / 86_400_000) + 1;
}

/** A ratio as a percentage with one decimal, or a dash. */
export function percent(part, whole) {
  if (!whole) return "—";
  return `${((part / whole) * 100).toFixed(1)}%`;
}

/** The first twelve characters of an object id, as the rest of the app shows them. */
export const shortId = (sha) => (sha ? sha.slice(0, 12) : "—");
