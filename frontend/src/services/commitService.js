import { api } from "./api";

const base = (owner, repo) => `/repositories/${owner}/${repo}`;

export const commitService = {
  /**
   * History reachable from a revision, optionally narrowed to one path.
   *
   * `path` is left off when blank rather than sent empty. The two mean the same
   * thing to the server — the root, whose history is the whole history — but
   * only one of them keeps the request honest about whether a filter was asked
   * for.
   */
  async history(owner, repo, { ref, limit = 30, path } = {}) {
    const target = path?.trim() ? path.trim() : undefined;
    const { data } = await api.get(`${base(owner, repo)}/commits`, {
      params: { ref, limit, path: target },
    });
    return data;
  },

  async detail(owner, repo, sha) {
    const { data } = await api.get(`${base(owner, repo)}/commits/${sha}`);
    return data;
  },

  /** What a commit changed, line by line, against its first parent. */
  async commitDiff(owner, repo, sha, { path } = {}) {
    const { data } = await api.get(`${base(owner, repo)}/commits/${sha}/diff`, { params: { path } });
    return data;
  },

  /**
   * The line-level difference between two revisions.
   *
   * Used for the compare page in preference to /compare, which returns the same
   * file-level changes without the lines - everything it reports can be derived
   * from this, so asking for both would be two requests for one answer.
   */
  async compare(owner, repo, { base: from, head, path } = {}) {
    const { data } = await api.get(`${base(owner, repo)}/diff`, {
      params: { base: from, head, path },
    });
    return data;
  },
};
