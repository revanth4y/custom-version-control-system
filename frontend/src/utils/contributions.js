/**
 * Laying out a year of daily commit counts as a calendar.
 *
 * The server sends every day in the range, empty ones included, so nothing here
 * fills gaps or invents a date. This only arranges the days it was given into
 * weeks and decides how dark each square should be.
 */

/** Zero, then four levels of activity. */
export const INTENSITY_LEVELS = 5;

/**
 * How dark a day's square is, from 0 to 4.
 *
 * The scale is relative to the busiest day in the window rather than to fixed
 * commit counts. A fixed scale would render a quiet repository uniformly pale
 * and a busy one uniformly dark, which says less than the shape of someone's
 * own activity does. A day with any commits at all is never level 0, so real
 * work is never drawn as absence.
 */
export function intensityOf(count, max) {
  if (!count || count <= 0) return 0;
  if (!max || max <= 0) return 0;
  const level = Math.ceil((count / max) * (INTENSITY_LEVELS - 1));
  return Math.min(Math.max(level, 1), INTENSITY_LEVELS - 1);
}

export function busiestDay(days) {
  return (days ?? []).reduce((most, day) => Math.max(most, day.count ?? 0), 0);
}

/** Parsed as UTC, matching the server, which buckets commits by UTC day. */
function dateOf(iso) {
  const [year, month, day] = (iso ?? "").split("-").map(Number);
  return new Date(Date.UTC(year, (month ?? 1) - 1, day ?? 1));
}

/** Sunday is 0, matching the row order of the grid. */
export function weekdayOf(iso) {
  return dateOf(iso).getUTCDay();
}

/**
 * The days arranged into columns of seven, one column per week.
 *
 * The first column is padded with nulls so that every row is one weekday: the
 * range rarely begins on a Sunday, and without the padding the whole calendar
 * would be sheared by a day or two and every row would mean nothing.
 */
export function toWeeks(days) {
  const list = days ?? [];
  if (list.length === 0) return [];

  const weeks = [];
  let current = new Array(weekdayOf(list[0].date)).fill(null);

  for (const day of list) {
    current.push(day);
    if (current.length === 7) {
      weeks.push(current);
      current = [];
    }
  }
  // The last week is padded too, so every column is the same height.
  if (current.length > 0) {
    weeks.push([...current, ...new Array(7 - current.length).fill(null)]);
  }
  return weeks;
}

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/**
 * Which columns should carry a month label.
 *
 * A label goes on the first week that contains that month's first days, and
 * only when there is room for the name - labelling a single-column sliver at
 * either end produces overlapping text.
 */
export function monthLabels(weeks) {
  const labels = [];
  let previous = null;

  (weeks ?? []).forEach((week, index) => {
    const firstReal = week.find(Boolean);
    if (!firstReal) return;

    const month = dateOf(firstReal.date).getUTCMonth();
    if (month !== previous) {
      labels.push({ index, label: MONTHS[month] });
      previous = month;
    }
  });

  // Drop a label in the final column, which has no width to print into.
  return labels.filter((label) => label.index < (weeks?.length ?? 0) - 1);
}

/** "3 commits on 14 March 2026" — the whole of what a square means, in words. */
export function describeDay(day) {
  if (!day) return "";
  const count = day.count ?? 0;
  const when = dateOf(day.date).toLocaleDateString(undefined, {
    day: "numeric",
    month: "long",
    year: "numeric",
    timeZone: "UTC",
  });
  const commits = count === 1 ? "1 commit" : `${count} commits`;
  return `${count === 0 ? "No commits" : commits} on ${when}`;
}
