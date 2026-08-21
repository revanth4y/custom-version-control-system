import { Navigate, useRoutes } from "react-router-dom";

import AppShell from "../components/layout/AppShell";
import Dashboard from "../pages/Dashboard";
import DesignSystem from "../pages/DesignSystem";
import Login from "../pages/Login";
import Signup from "../pages/Signup";
import { useAuth } from "../hooks/useAuth";

/** Renders children only for signed-in users; anonymous callers go to the login page. */
const RequireAuth = ({ children }) => {
  const { currentUser, loading } = useAuth();

  if (loading) return null;
  return currentUser ? children : <Navigate to="/login" replace />;
};

/** Keeps signed-in users away from the login and signup pages. */
const RequireAnonymous = ({ children }) => {
  const { currentUser, loading } = useAuth();

  if (loading) return null;
  return currentUser ? <Navigate to="/" replace /> : children;
};

/**
 * Static paths are declared before the dynamic ones. React Router ranks static
 * segments higher regardless, but the ordering keeps the intent readable: no
 * username can shadow /login.
 */
const AppRoutes = () =>
  useRoutes([
    { path: "/login", element: <RequireAnonymous><Login /></RequireAnonymous> },
    { path: "/signup", element: <RequireAnonymous><Signup /></RequireAnonymous> },

    // Internal design reference, deliberately outside the shell's auth guard so
    // the theme can be reviewed without signing in.
    { path: "/_design", element: <DesignSystem /> },

    {
      path: "/",
      element: <RequireAuth><AppShell /></RequireAuth>,
      children: [{ index: true, element: <Dashboard /> }],
    },

    { path: "*", element: <Navigate to="/" replace /> },
  ]);

export default AppRoutes;
