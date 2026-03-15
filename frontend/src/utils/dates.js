const MINUTE = 60;
const HOUR = MINUTE * 60;
const DAY = HOUR * 24;
const MONTH = DAY * 30;
const YEAR = DAY * 365;

/**
 * A short, human-readable age, such as "3 hours ago".
 *
 * Timestamps in this application are almost always read as "how recent is
 * this", so the relative form answers the question directly. The absolute value
 * is still available as a title attribute wherever precision matters.
 */
export function formatRelativeTime(isoTimestamp) {
  if (!isoTimestamp) return "unknown";

  const then = new Date(isoTimestamp).getTime();
  if (Number.isNaN(then)) return "unknown";

  const seconds = Math.round((Date.now() - then) / 1000);
  if (seconds < 45) return "just now";
  if (seconds < HOUR) return plural(Math.round(seconds / MINUTE), "minute");
  if (seconds < DAY) return plural(Math.round(seconds / HOUR), "hour");
  if (seconds < MONTH) return plural(Math.round(seconds / DAY), "day");
  if (seconds < YEAR) return plural(Math.round(seconds / MONTH), "month");
  return plural(Math.round(seconds / YEAR), "year");
}

/** The full timestamp, for tooltips where the exact moment matters. */
export function formatAbsoluteTime(isoTimestamp) {
  if (!isoTimestamp) return "";
  const date = new Date(isoTimestamp);
  return Number.isNaN(date.getTime()) ? "" : date.toLocaleString();
}

function plural(count, unit) {
  return `${count} ${unit}${count === 1 ? "" : "s"} ago`;
}
