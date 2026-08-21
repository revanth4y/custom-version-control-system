import { api } from "./api";

export const insightsService = {
  /**
   * Aggregate facts about a repository.
   *
   * Computed from the object store on every call rather than stored, so the
   * figures cannot drift away from the history they describe.
   */
  async forRepository(owner, name) {
    const { data } = await api.get(`/repositories/${owner}/${name}/insights`);
    return data;
  },
};
