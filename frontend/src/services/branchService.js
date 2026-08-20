import { api } from "./api";

const base = (owner, repo) => `/repositories/${owner}/${repo}`;

export const branchService = {
  async list(owner, repo) {
    const { data } = await api.get(`${base(owner, repo)}/branches`);
    return data;
  },

  async create(owner, repo, { name, startPoint }) {
    const { data } = await api.post(`${base(owner, repo)}/branches`, { name, startPoint });
    return data;
  },

  // The branch travels as a query parameter because names may contain slashes,
  // which a path segment cannot carry without encoding gymnastics.
  async remove(owner, repo, name) {
    await api.delete(`${base(owner, repo)}/branches`, { params: { name } });
  },

  async head(owner, repo) {
    const { data } = await api.get(`${base(owner, repo)}/head`);
    return data;
  },

  async setHead(owner, repo, branch) {
    const { data } = await api.put(`${base(owner, repo)}/head`, { branch });
    return data;
  },
};
