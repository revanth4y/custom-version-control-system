import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";

import { useAuth } from "../../hooks/useAuth";
import { errorMessage } from "../../services/api";
import mark from "../../assets/gitforge-mark.svg";
import "./auth.css";

const Signup = () => {
  const [username, setUsername] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const { signup } = useAuth();
  const navigate = useNavigate();

  const handleSubmit = async (event) => {
    event.preventDefault();
    setError(null);
    setLoading(true);

    try {
      await signup({ username, email, password });
      navigate("/", { replace: true });
    } catch (err) {
      setError(errorMessage(err, "Could not create the account"));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="auth-page">
      <form className="auth-card" onSubmit={handleSubmit}>
        <img className="auth-card__mark" src={mark} alt="" width="44" height="44" />
        <h1 className="auth-card__title">Create your account</h1>

        {error && (
          <p className="auth-card__error" role="alert">
            {error}
          </p>
        )}

        <label className="auth-card__label" htmlFor="username">
          Username
        </label>
        <input
          id="username"
          className="auth-card__input"
          type="text"
          autoComplete="username"
          required
          value={username}
          onChange={(event) => setUsername(event.target.value)}
        />

        <label className="auth-card__label" htmlFor="email">
          Email address
        </label>
        <input
          id="email"
          className="auth-card__input"
          type="email"
          autoComplete="email"
          required
          value={email}
          onChange={(event) => setEmail(event.target.value)}
        />

        <label className="auth-card__label" htmlFor="password">
          Password
        </label>
        <input
          id="password"
          className="auth-card__input"
          type="password"
          autoComplete="new-password"
          required
          minLength={8}
          value={password}
          onChange={(event) => setPassword(event.target.value)}
        />
        <p className="auth-card__hint">At least 8 characters.</p>

        <button className="auth-card__submit" type="submit" disabled={loading}>
          {loading ? "Creating account…" : "Create account"}
        </button>

        <p className="auth-card__footer">
          Already have an account? <Link to="/login">Sign in</Link>
        </p>
      </form>
    </div>
  );
};

export default Signup;
