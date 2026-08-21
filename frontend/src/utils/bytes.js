const UNITS = ["B", "KB", "MB", "GB"];

/** A short human-readable size, such as "1.4 KB". */
export function formatBytes(size) {
  if (size == null || Number.isNaN(size)) return "";
  if (size === 0) return "0 B";

  let value = size;
  let unit = 0;
  while (value >= 1024 && unit < UNITS.length - 1) {
    value /= 1024;
    unit += 1;
  }
  // Whole bytes never need a decimal; larger units read better with one.
  const rounded = unit === 0 ? value : Math.round(value * 10) / 10;
  return `${rounded} ${UNITS[unit]}`;
}

/** Splits text into lines for numbered display, without a phantom final line. */
export function toLines(text) {
  if (!text) return [];
  const body = text.endsWith("\n") ? text.slice(0, -1) : text;
  return body.split("\n");
}

/** The file extension, lowercased, or an empty string. */
export function extensionOf(path) {
  const file = (path ?? "").split("/").pop() ?? "";
  const dot = file.lastIndexOf(".");
  return dot > 0 ? file.slice(dot + 1).toLowerCase() : "";
}
