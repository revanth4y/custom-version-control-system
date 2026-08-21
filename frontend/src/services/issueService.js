import { api } from "./api";

const repo = (owner, name) => `/repositories/${owner}/${name}`;

/**
 * Issues and their comments.
 *
 * Reads are scoped by owner, name and issue number; writes address the record
 * by its own id. That asymmetry is the server's, not a choice made here, so
 * callers have to carry both the number (for links) and the id (for writes).
 */
export const issueService = {
  async list(owner, name, { status } = {}) {
    const { data } = await api.get(`${repo(owner, name)}/issues`, { params: { status } });
    return data;
  },

  async get(owner, name, number) {
    const { data } = await api.get(`${repo(owner, name)}/issues/${number}`);
    return data;
  },

  async create(owner, name, { title, body }) {
    const { data } = await api.post(`${repo(owner, name)}/issues`, { title, body });
    return data;
  },

  /** Partial: whatever is omitted is left unchanged by the server. */
  async update(id, { title, body, status }) {
    const { data } = await api.patch(`/issues/${id}`, { title, body, status });
    return data;
  },

  async remove(id) {
    await api.delete(`/issues/${id}`);
  },

  async listComments(owner, name, number) {
    const { data } = await api.get(`${repo(owner, name)}/issues/${number}/comments`);
    return data;
  },

  async addComment(owner, name, number, body) {
    const { data } = await api.post(`${repo(owner, name)}/issues/${number}/comments`, { body });
    return data;
  },

  async updateComment(id, body) {
    const { data } = await api.patch(`/issue-comments/${id}`, { body });
    return data;
  },

  async removeComment(id) {
    await api.delete(`/issue-comments/${id}`);
  },
};
