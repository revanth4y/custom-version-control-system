/**
 * Generated avatars, derived from a username.
 *
 * Kept out of the component file so that module exports only a component,
 * which is what lets fast refresh work reliably during development.
 */

export function avatarFor(username) {
  const name = username?.trim() || "?";
  const hue = [...name].reduce((total, character) => total + character.charCodeAt(0) * 7, 0) % 360;
  const initial = name[0].toUpperCase();

  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">
    <rect width="32" height="32" fill="hsl(${hue} 42% 30%)"/>
    <text x="16" y="21.5" font-family="-apple-system, Segoe UI, sans-serif" font-size="15"
          font-weight="600" fill="hsl(${hue} 65% 80%)" text-anchor="middle">${escapeXml(initial)}</text>
  </svg>`;

  return `data:image/svg+xml;utf8,${encodeURIComponent(svg)}`;
}

/** The initial is user-supplied, so it cannot be dropped into markup unescaped. */
function escapeXml(value) {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&apos;");
}
