import { Link } from "react-router-dom";
import { useAuth } from "../hooks/useAuth";
import mark from "../assets/gitforge-mark.svg";
import "./navbar.css";

const Navbar = () => {
  const { currentUser, logout } = useAuth();

  return (
    <nav className="app-nav">
      <Link to="/" className="app-nav__brand">
        <img src={mark} alt="" width="28" height="28" />
        <span>GitForge</span>
      </Link>

      <div className="app-nav__links">
        {currentUser && (
          <Link to={`/${currentUser.username}`}>{currentUser.username}</Link>
        )}
        <button type="button" className="app-nav__logout" onClick={logout}>
          Sign out
        </button>
      </div>
    </nav>
  );
};

export default Navbar;
