import { api } from "./api";

export const userService = {
  /** A public profile. Never carries another account's email. */
  async profile(username) {
    const { data } = await api.get(`/users/${username}`);
    return data;
  },

  /** Updates the caller's own account. Partial: omitted fields are unchanged. */
  async updateOwnProfile(changes) {
    const { data } = await api.patch("/users/me", changes);
    return data;
  },

  /**
   * Daily commit counts for one person.
   *
   * The server fills every day in the range, including empty ones, and applies
   * repository visibility before counting - so a stranger's view of a profile
   * never includes private work.
   */
  async contributions(username, { from, to } = {}) {
    const { data } = await api.get(`/users/${username}/contributions`, { params: { from, to } });
    return data;
  },
};
