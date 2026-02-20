import { useCallback, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";

import Navbar from "../Navbar";
import { useAuth } from "../../hooks/useAuth";
import { errorMessage } from "../../services/api";
import { repoService } from "../../services/repoService";
import "./dashboard.css";

const Dashboard = () => {
  const { currentUser } = useAuth();

  const [ownRepos, setOwnRepos] = useState([]);
  const [publicRepos, setPublicRepos] = useState([]);
  const [searchQuery, setSearchQuery] = useState("");
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);

  const [newRepo, setNewRepo] = useState({ name: "", description: "", visibility: "PUBLIC" });
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState(null);

  const load = useCallback(async () => {
    if (!currentUser) return;
    setError(null);

    try {
      const [own, discovery] = await Promise.all([
        repoService.listByOwner(currentUser.username),
        repoService.listPublic({ size: 10 }),
      ]);
      setOwnRepos(own);
      setPublicRepos(discovery.content);
    } catch (err) {
      setError(errorMessage(err, "Could not load repositories"));
    } finally {
      setLoading(false);
    }
  }, [currentUser]);

  useEffect(() => {
    load();
  }, [load]);

  const visibleRepos = useMemo(() => {
    const query = searchQuery.trim().toLowerCase();
    if (!query) return ownRepos;
    return ownRepos.filter((repo) => repo.name.toLowerCase().includes(query));
  }, [ownRepos, searchQuery]);

  const handleCreate = async (event) => {
    event.preventDefault();
    setCreateError(null);
    setCreating(true);

    try {
      await repoService.create(newRepo);
      setNewRepo({ name: "", description: "", visibility: "PUBLIC" });
      await load();
    } catch (err) {
      setCreateError(errorMessage(err, "Could not create the repository"));
    } finally {
      setCreating(false);
    }
  };

  return (
    <>
      <Navbar />

      <section className="dashboard">
        <main className="dashboard__main">
          <h2 className="dashboard__heading">Your repositories</h2>

          <input
            className="dashboard__search"
            type="search"
            placeholder="Find a repository…"
            value={searchQuery}
            onChange={(event) => setSearchQuery(event.target.value)}
          />

          {error && <p className="dashboard__error">{error}</p>}

          {loading && <p className="dashboard__empty">Loading…</p>}

          {!loading && visibleRepos.length === 0 && (
            <p className="dashboard__empty">
              {ownRepos.length === 0
                ? "You have no repositories yet. Create one to get started."
                : "No repositories match that search."}
            </p>
          )}

          <ul className="repo-list">
            {visibleRepos.map((repo) => (
              <li key={repo.id} className="repo-list__item">
                <div className="repo-list__header">
                  <Link className="repo-list__name" to={`/${repo.ownerUsername}`}>
                    {repo.name}
                  </Link>
                  <span className="repo-list__badge">{repo.visibility.toLowerCase()}</span>
                </div>
                {repo.description && <p className="repo-list__description">{repo.description}</p>}
              </li>
            ))}
          </ul>
        </main>

        <aside className="dashboard__aside">
          <h3 className="dashboard__heading">New repository</h3>

          <form className="new-repo" onSubmit={handleCreate}>
            {createError && <p className="dashboard__error">{createError}</p>}

            <label className="new-repo__label" htmlFor="repo-name">
              Name
            </label>
            <input
              id="repo-name"
              className="new-repo__input"
              required
              value={newRepo.name}
              onChange={(event) => setNewRepo({ ...newRepo, name: event.target.value })}
            />

            <label className="new-repo__label" htmlFor="repo-description">
              Description
            </label>
            <input
              id="repo-description"
              className="new-repo__input"
              value={newRepo.description}
              onChange={(event) => setNewRepo({ ...newRepo, description: event.target.value })}
            />

            <label className="new-repo__label" htmlFor="repo-visibility">
              Visibility
            </label>
            <select
              id="repo-visibility"
              className="new-repo__input"
              value={newRepo.visibility}
              onChange={(event) => setNewRepo({ ...newRepo, visibility: event.target.value })}
            >
              <option value="PUBLIC">Public</option>
              <option value="PRIVATE">Private</option>
            </select>

            <button className="new-repo__submit" type="submit" disabled={creating}>
              {creating ? "Creating…" : "Create repository"}
            </button>
          </form>

          <h3 className="dashboard__heading">Explore</h3>
          {publicRepos.length === 0 ? (
            <p className="dashboard__empty">Nothing public yet.</p>
          ) : (
            <ul className="repo-list repo-list--compact">
              {publicRepos.map((repo) => (
                <li key={repo.id} className="repo-list__item">
                  <span className="repo-list__name">
                    {repo.ownerUsername}/{repo.name}
                  </span>
                  {repo.description && <p className="repo-list__description">{repo.description}</p>}
                </li>
              ))}
            </ul>
          )}
        </aside>
      </section>
    </>
  );
};

export default Dashboard;
