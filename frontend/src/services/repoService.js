import { api } from "./api";

export const repoService = {
  async listPublic({ page = 0, size = 20 } = {}) {
    const { data } = await api.get("/repositories", { params: { page, size } });
    return data;
  },

  async listByOwner(username) {
    const { data } = await api.get(`/users/${username}/repositories`);
    return data;
  },

  async get(owner, name) {
    const { data } = await api.get(`/repositories/${owner}/${name}`);
    return data;
  },

  async create({ name, description, visibility }) {
    const { data } = await api.post("/repositories", { name, description, visibility });
    return data;
  },

  async update(id, changes) {
    const { data } = await api.patch(`/repositories/${id}`, changes);
    return data;
  },

  async remove(id) {
    await api.delete(`/repositories/${id}`);
  },
};
