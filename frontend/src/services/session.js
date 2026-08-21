const TOKEN_KEY = "gitforge.token";
const USER_KEY = "gitforge.user";

/**
 * Client-side session storage.
 *
 * Tokens are held in localStorage, which is readable by any script on the page.
 * Moving to an httpOnly refresh cookie with a short-lived in-memory access token
 * is planned for the security-hardening phase.
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
