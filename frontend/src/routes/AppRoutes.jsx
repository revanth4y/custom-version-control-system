import { Navigate, useRoutes } from "react-router-dom";

import AppShell from "../components/layout/AppShell";
import BlobView from "../pages/BlobView";
import BranchList from "../pages/BranchList";
import CommitDetail from "../pages/CommitDetail";
import CommitHistory from "../pages/CommitHistory";
import Compare from "../pages/Compare";
import IssueDetailPage from "../pages/IssueDetailPage";
import MergePage from "../pages/MergePage";
import NewIssue from "../pages/NewIssue";
import RepositoryIssues from "../pages/RepositoryIssues";
import CreateRepository from "../pages/CreateRepository";
import Dashboard from "../pages/Dashboard";
import DesignSystem from "../pages/DesignSystem";
import Login from "../pages/Login";
import RepositoryCode from "../pages/RepositoryCode";
import RepositoryLayout from "../pages/RepositoryLayout";
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
 * username can shadow /login or /new.
 *
 * Refs and paths are splats because both may contain slashes — `feature/login`
 * is a valid branch, and `src/main/App.java` is a valid path.
 */
const AppRoutes = () =>
  useRoutes([
    { path: "/login", element: <RequireAnonymous><Login /></RequireAnonymous> },
    { path: "/signup", element: <RequireAnonymous><Signup /></RequireAnonymous> },

    // Internal design reference, outside the auth guard so the theme can be
    // reviewed without signing in.
    { path: "/_design", element: <DesignSystem /> },

    {
      path: "/",
      element: <RequireAuth><AppShell /></RequireAuth>,
      children: [
        { index: true, element: <Dashboard /> },
        { path: "new", element: <CreateRepository /> },
        {
          path: ":username/:repo",
          element: <RepositoryLayout />,
          children: [
            { index: true, element: <RepositoryCode /> },
            { path: "tree/:ref/*", element: <RepositoryCode /> },
            { path: "tree/:ref", element: <RepositoryCode /> },
            { path: "blob/:ref/*", element: <BlobView /> },
            { path: "branches", element: <BranchList /> },
            { path: "commits", element: <CommitHistory /> },
            { path: "commits/:ref", element: <CommitHistory /> },
            { path: "commit/:sha", element: <CommitDetail /> },
            { path: "compare", element: <Compare /> },
            { path: "merge", element: <MergePage /> },
            { path: "issues", element: <RepositoryIssues /> },
            // Declared before the number so "new" is never read as one; React
            // Router ranks static segments higher regardless.
            { path: "issues/new", element: <NewIssue /> },
            { path: "issues/:number", element: <IssueDetailPage /> },
          ],
        },
      ],
    },

    { path: "*", element: <Navigate to="/" replace /> },
  ]);

export default AppRoutes;
