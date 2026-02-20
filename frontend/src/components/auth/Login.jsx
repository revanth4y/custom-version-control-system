import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";

import { useAuth } from "../../hooks/useAuth";
import { errorMessage } from "../../services/api";
import mark from "../../assets/gitforge-mark.svg";
import "./auth.css";

const Login = () => {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const { login } = useAuth();
  const navigate = useNavigate();

  const handleSubmit = async (event) => {
    event.preventDefault();
    setError(null);
    setLoading(true);

    try {
      await login({ email, password });
      navigate("/", { replace: true });
    } catch (err) {
      setError(errorMessage(err, "Invalid email or password"));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="auth-page">
      <form className="auth-card" onSubmit={handleSubmit}>
        <img className="auth-card__mark" src={mark} alt="" width="44" height="44" />
        <h1 className="auth-card__title">Sign in to GitForge</h1>

        {error && (
          <p className="auth-card__error" role="alert">
            {error}
          </p>
        )}

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
          autoComplete="current-password"
          required
          value={password}
          onChange={(event) => setPassword(event.target.value)}
        />

        <button className="auth-card__submit" type="submit" disabled={loading}>
          {loading ? "Signing in…" : "Sign in"}
        </button>

        <p className="auth-card__footer">
          New to GitForge? <Link to="/signup">Create an account</Link>
        </p>
      </form>
    </div>
  );
};

export default Login;
