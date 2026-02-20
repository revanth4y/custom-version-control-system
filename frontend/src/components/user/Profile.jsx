import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";

import Navbar from "../Navbar";
import { useAuth } from "../../hooks/useAuth";
import { errorMessage } from "../../services/api";
import { userService } from "../../services/userService";
import { repoService } from "../../services/repoService";
import "./profile.css";

const Profile = () => {
  const { username } = useParams();
  const { currentUser } = useAuth();

  const [profile, setProfile] = useState(null);
  const [repos, setRepos] = useState([]);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);

    Promise.all([userService.getProfile(username), repoService.listByOwner(username)])
      .then(([profileData, repoData]) => {
        if (cancelled) return;
        setProfile(profileData);
        setRepos(repoData);
      })
      .catch((err) => {
        if (!cancelled) setError(errorMessage(err, "Could not load this profile"));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [username]);

  const isOwnProfile = currentUser?.username === username;

  return (
    <>
      <Navbar />

      <div className="profile">
        {loading && <p className="profile__status">Loading…</p>}
        {error && <p className="profile__status profile__status--error">{error}</p>}

        {profile && (
          <>
            <header className="profile__header">
              <div className="profile__avatar" aria-hidden="true">
                {profile.username.charAt(0).toUpperCase()}
              </div>
              <div>
                <h1 className="profile__name">{profile.displayName ?? profile.username}</h1>
                <p className="profile__username">{profile.username}</p>
                {profile.bio && <p className="profile__bio">{profile.bio}</p>}
                <p className="profile__joined">
                  Joined {new Date(profile.createdAt).toLocaleDateString()}
                </p>
              </div>
            </header>

            <section>
              <h2 className="profile__section-title">
                Repositories <span className="profile__count">{repos.length}</span>
              </h2>

              {repos.length === 0 ? (
                <p className="profile__status">
                  {isOwnProfile ? "You have no repositories yet." : "No public repositories."}
                </p>
              ) : (
                <ul className="profile__repos">
                  {repos.map((repo) => (
                    <li key={repo.id} className="profile__repo">
                      <div className="profile__repo-header">
                        <span className="profile__repo-name">{repo.name}</span>
                        <span className="profile__repo-badge">{repo.visibility.toLowerCase()}</span>
                      </div>
                      {repo.description && (
                        <p className="profile__repo-description">{repo.description}</p>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </section>

            {/*
              The contribution graph is intentionally omitted until commits exist.
              It previously rendered randomly generated data, which misrepresents
              activity; it returns in the phase that adds real commit history.
            */}
          </>
        )}
      </div>
    </>
  );
};

export default Profile;
