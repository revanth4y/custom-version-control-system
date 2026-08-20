import { api } from "./api";

const base = (owner, repo) => `/repositories/${owner}/${repo}`;

export const contentService = {
  /** Directory listing at a revision. Omit `path` for the repository root. */
  async tree(owner, repo, { ref, path } = {}) {
    const { data } = await api.get(`${base(owner, repo)}/tree`, { params: { ref, path } });
    return data;
  },

  /**
   * A file's contents.
   *
   * The response carries an explicit `binary` flag and `encoding`, so callers
   * never have to guess whether `content` is text or base64.
   */
  async blob(owner, repo, { ref, path }) {
    const { data } = await api.get(`${base(owner, repo)}/blob`, { params: { ref, path } });
    return data;
  },

  async putContent(owner, repo, { branch, path, content, encoding, mode, message }) {
    const { data } = await api.put(`${base(owner, repo)}/contents`, {
      branch, path, content, encoding, mode, message,
    });
    return data;
  },
};
