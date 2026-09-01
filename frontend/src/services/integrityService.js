import { api } from "./api";

export const integrityService = {
  /**
   * Asks the server to re-read and re-hash the repository's stored objects.
   *
   * The work happens on the server because only it can see the framed bytes an
   * id is taken over; the browser is in no position to check a hash and this
   * client does not pretend otherwise. What comes back is the server's finding.
   *
   * A repository with damaged objects still resolves: corruption is the result
   * the check exists to report, not a failed request.
   */
  async forRepository(owner, name) {
    const { data } = await api.get(`/repositories/${owner}/${name}/integrity`);
    return data;
  },
};
