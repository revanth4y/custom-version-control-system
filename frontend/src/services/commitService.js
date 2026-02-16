import { api } from "./api";

const base = (owner, repo) => `/repositories/${owner}/${repo}`;

export const commitService = {
  async history(owner, repo, { ref, limit = 30 } = {}) {
    const { data } = await api.get(`${base(owner, repo)}/commits`, { params: { ref, limit } });
    return data;
  },

  async detail(owner, repo, sha) {
    const { data } = await api.get(`${base(owner, repo)}/commits/${sha}`);
    return data;
  },
};
