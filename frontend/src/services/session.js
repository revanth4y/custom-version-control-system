const TOKEN_KEY = "gitforge.token";
const USER_KEY = "gitforge.user";

/**
 * Client-side session storage.
 *
 * Tokens are held in localStorage, which is readable by any script on the page —
 * so an XSS bug would expose the session. The Content-Security-Policy is what
 * stands in the way of that: no inline script, no external script origin.
 *
 * The alternative is an httpOnly cookie, which trades this exposure for CSRF to
 * defend against and a stateful refresh flow to build. That trade was considered
 * during hardening and deliberately not taken; it is a different design, not a
 * missing feature. See docs/security.md.
 */
export const session = {
  getToken() {
    return localStorage.getItem(TOKEN_KEY);
  },

  getUser() {
    const raw = localStorage.getItem(USER_KEY);
    if (!raw) return null;
    try {
      return JSON.parse(raw);
    } catch {
      // Corrupted entry: drop it rather than crashing every render.
      localStorage.removeItem(USER_KEY);
      return null;
    }
  },

  save(token, user) {
    localStorage.setItem(TOKEN_KEY, token);
    localStorage.setItem(USER_KEY, JSON.stringify(user));
  },

  clear() {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
  },
};
