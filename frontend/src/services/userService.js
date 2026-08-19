import { api } from "./api";

export const userService = {
  async getProfile(username) {
    const { data } = await api.get(`/users/${username}`);
    return data;
  },

  async updateOwnProfile(changes) {
    const { data } = await api.patch("/users/me", changes);
    return data;
  },
};
