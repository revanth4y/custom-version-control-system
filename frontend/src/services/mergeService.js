import { api } from "./api";

const base = (owner, repo) => `/repositories/${owner}/${repo}`;

export const mergeService = {
  /**
   * Merges one branch into another.
   *
   * Rejects on a conflict, because the server answers 409 - but the rejection
   * carries the complete result in its body. Callers pass `resultFrom` to
   * recover it rather than treating it as a failure.
   */
  async merge(owner, repo, { ourBranch, theirBranch, message } = {}) {
    const { data } = await api.post(`${base(owner, repo)}/merge`, {
      ourBranch,
      theirBranch,
      message,
    });
    return data;
  },
};
