import { api } from "./api";

const base = (owner, repo) => `/repositories/${owner}/${repo}/insights`;

/** Only sends the parameters that were actually chosen, so defaults stay the server's. */
const params = ({ from, to, bucket } = {}) => {
  const query = {};
  if (from) query.from = from;
  if (to) query.to = to;
  if (bucket) query.bucket = bucket;
  return { params: query };
};

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

  async activity(owner, name, range) {
    const { data } = await api.get(`${base(owner, name)}/activity`, params(range));
    return data;
  },

  /** The shape of the commit graph: merges, depth, roots, history span. */
  async commits(owner, name) {
    const { data } = await api.get(`${base(owner, name)}/commits`);
    return data;
  },

  /** Commits over time, gap-filled, at day or week grain. */
  async commitSeries(owner, name, range) {
    const { data } = await api.get(`${base(owner, name)}/commits/series`, params(range));
    return data;
  },

  async contributors(owner, name, range) {
    const { data } = await api.get(`${base(owner, name)}/contributors`, params(range));
    return data;
  },

  async branches(owner, name) {
    const { data } = await api.get(`${base(owner, name)}/branches`);
    return data;
  },

  async refs(owner, name) {
    const { data } = await api.get(`${base(owner, name)}/refs`);
    return data;
  },

  async tags(owner, name) {
    const { data } = await api.get(`${base(owner, name)}/tags`);
    return data;
  },

  async releases(owner, name) {
    const { data } = await api.get(`${base(owner, name)}/releases`);
    return data;
  },

  async issues(owner, name, range) {
    const { data } = await api.get(`${base(owner, name)}/issues`, params(range));
    return data;
  },

  async storage(owner, name) {
    const { data } = await api.get(`${base(owner, name)}/storage`);
    return data;
  },

  /**
   * Repository health.
   *
   * `scan` is opt-in and defaults to false. A scan walks every object and holds
   * the repository's exclusive lock while it does, so it must only ever be sent
   * because somebody asked for it — never on page load.
   */
  async health(owner, name, { scan = false } = {}) {
    const { data } = await api.get(`${base(owner, name)}/health`, {
      params: scan ? { scan: true } : {},
    });
    return data;
  },
};
