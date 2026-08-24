import { api } from "./api";

const base = (owner, repo) => `/repositories/${owner}/${repo}`;

export const contentService = {
  /**
   * Directory listing at a revision. Omit `path` for the repository root.
   *
   * `withLastCommit` asks the server to resolve the commit that last touched
   * each entry. It is the same single request either way — the server walks
   * history once for the whole listing rather than answering per file — so the
   * listing gains two columns without gaining a round trip.
   */
  async tree(owner, repo, { ref, path, withLastCommit } = {}) {
    const { data } = await api.get(`${base(owner, repo)}/tree`, {
      params: { ref, path, ...(withLastCommit ? { withLastCommit: true } : {}) },
    });
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
