import { Navigate, useRoutes } from "react-router-dom";

import Dashboard from "./components/dashboard/Dashboard";
import Profile from "./components/user/Profile";
import Login from "./components/auth/Login";
import Signup from "./components/auth/Signup";
import { useAuth } from "./hooks/useAuth";

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

const ProjectRoutes = () => {
  return useRoutes([
    {
      path: "/",
      element: (
        <RequireAuth>
          <Dashboard />
        </RequireAuth>
      ),
    },
    {
      path: "/login",
      element: (
        <RequireAnonymous>
          <Login />
        </RequireAnonymous>
      ),
    },
    {
      path: "/signup",
      element: (
        <RequireAnonymous>
          <Signup />
        </RequireAnonymous>
      ),
    },
    {
      path: "/:username",
      element: (
        <RequireAuth>
          <Profile />
        </RequireAuth>
      ),
    },
    { path: "*", element: <Navigate to="/" replace /> },
  ]);
};

export default ProjectRoutes;
