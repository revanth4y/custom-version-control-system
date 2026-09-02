import { api } from "./api";

const base = (owner, repo) => `/repositories/${owner}/${repo}`;

/**
 * Tags and releases.
 *
 * They share a file because they share a surface: the releases page shows both,
 * and splitting them would mean two modules whose only callers are each other's
 * neighbours.
 *
 * Tag names travel as query parameters because they may contain slashes;
 * releases are addressed by their own id, so those go in the path.
 */
export const releaseService = {
  async listTags(owner, repo) {
    const { data } = await api.get(`${base(owner, repo)}/tags`);
    return data;
  },

  async getTag(owner, repo, name) {
    const { data } = await api.get(`${base(owner, repo)}/tag`, { params: { name } });
    return data;
  },

  // A message produces an annotated tag; leaving it out produces a lightweight one.
  async createTag(owner, repo, { name, target, message }) {
    const { data } = await api.post(`${base(owner, repo)}/tags`, { name, target, message });
    return data;
  },

  async removeTag(owner, repo, name) {
    await api.delete(`${base(owner, repo)}/tags`, { params: { name } });
  },

  async list(owner, repo) {
    const { data } = await api.get(`${base(owner, repo)}/releases`);
    return data;
  },

  async get(owner, repo, id) {
    const { data } = await api.get(`${base(owner, repo)}/releases/${id}`);
    return data;
  },

  async create(owner, repo, { tag, name, body, draft, prerelease }) {
    const { data } = await api.post(`${base(owner, repo)}/releases`, {
      tag,
      name,
      body,
      draft,
      prerelease,
    });
    return data;
  },

  // Metadata only. There is deliberately no tag field: a release cannot be
  // re-pointed once it names something.
  async update(owner, repo, id, changes) {
    const { data } = await api.patch(`${base(owner, repo)}/releases/${id}`, changes);
    return data;
  },

  async remove(owner, repo, id) {
    await api.delete(`${base(owner, repo)}/releases/${id}`);
  },
};
