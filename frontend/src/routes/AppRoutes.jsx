import { lazy } from "react";
import { Navigate, useRoutes } from "react-router-dom";

import AppShell from "../components/layout/AppShell";
import RepositoryLayout from "../pages/RepositoryLayout";
import { useAuth } from "../hooks/useAuth";

/**
 * Pages are loaded on demand.
 *
 * Every page used to be in the first download, so a visitor reading one public
 * file also fetched the merge screen, the diff viewer and the design reference.
 * Splitting by route means the initial load carries the shell and the page
 * actually asked for.
 *
 * The shell and the repository frame stay eager: they render on essentially
 * every route, so deferring them would only add a request before anything can
 * appear.
 */
const BlobView = lazy(() => import("../pages/BlobView"));
const BranchList = lazy(() => import("../pages/BranchList"));
const Releases = lazy(() => import("../pages/Releases"));
const ReleaseDetail = lazy(() => import("../pages/ReleaseDetail"));
const CommitDetail = lazy(() => import("../pages/CommitDetail"));
const CommitHistory = lazy(() => import("../pages/CommitHistory"));
const Compare = lazy(() => import("../pages/Compare"));
const CreateRepository = lazy(() => import("../pages/CreateRepository"));
const DagExplorer = lazy(() => import("../pages/DagExplorer"));
const Dashboard = lazy(() => import("../pages/Dashboard"));
const Insights = lazy(() => import("../pages/Insights"));
const IntegrityCentre = lazy(() => import("../pages/IntegrityCentre"));
const IssueDetailPage = lazy(() => import("../pages/IssueDetailPage"));
const Login = lazy(() => import("../pages/Login"));
const MergePage = lazy(() => import("../pages/MergePage"));
const MerkleExplorer = lazy(() => import("../pages/MerkleExplorer"));
const NewIssue = lazy(() => import("../pages/NewIssue"));
const RepositoryCode = lazy(() => import("../pages/RepositoryCode"));
const RepositoryIssues = lazy(() => import("../pages/RepositoryIssues"));
const RepositorySettings = lazy(() => import("../pages/RepositorySettings"));
const Signup = lazy(() => import("../pages/Signup"));
const UserProfile = lazy(() => import("../pages/UserProfile"));

/**
 * The design reference, in development only.
 *
 * It is a tool for building this interface, not part of it, and it used to be
 * shipped to every visitor. `import.meta.env.DEV` is statically replaced at
 * build time, so the whole branch - and the page behind it - is removed from
 * the production bundle rather than merely being unreachable in it.
 */
const DesignSystem = import.meta.env.DEV ? lazy(() => import("../pages/DesignSystem")) : null;

/**
 * Guards a route that genuinely needs an identity.
 *
 * Reading a public repository does not: the server serves it to anyone, and
 * wrapping it here would contradict what `visibility: PUBLIC` means. This is
 * for the things that are actually someone's own - their dashboard, creating a
 * repository, filing an issue.
 *
 * It is a courtesy in any case. The server authorises every write regardless of
 * what the interface chose to render.
 */
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

    ...(DesignSystem ? [{ path: "/_design", element: <DesignSystem /> }] : []),

    {
      path: "/",
      element: <AppShell />,
      children: [
        // A dashboard is by definition someone's own.
        { index: true, element: <RequireAuth><Dashboard /></RequireAuth> },
        { path: "new", element: <RequireAuth><CreateRepository /></RequireAuth> },

        // Public: the server serves these to anonymous callers, and refuses
        // anything private with a 404 that hides its existence.
        { path: ":username", element: <UserProfile /> },
        {
          path: ":username/:repo",
          element: <RepositoryLayout />,
          children: [
            { index: true, element: <RepositoryCode /> },
            { path: "tree/:ref/*", element: <RepositoryCode /> },
            { path: "tree/:ref", element: <RepositoryCode /> },
            { path: "blob/:ref/*", element: <BlobView /> },
            { path: "branches", element: <BranchList /> },
            { path: "releases", element: <Releases /> },
            { path: "releases/:releaseId", element: <ReleaseDetail /> },
            { path: "commits", element: <CommitHistory /> },
            { path: "commits/:ref", element: <CommitHistory /> },
            { path: "commit/:sha", element: <CommitDetail /> },
            { path: "graph", element: <DagExplorer /> },
            { path: "graph/:ref", element: <DagExplorer /> },
            { path: "merkle", element: <MerkleExplorer /> },
            { path: "merkle/:ref", element: <MerkleExplorer /> },
            // No :ref variant: the scan covers the whole object store, including
            // objects no branch reaches, so a revision would promise a narrowing
            // it does not do.
            { path: "integrity", element: <IntegrityCentre /> },
            { path: "compare", element: <Compare /> },
            { path: "insights", element: <Insights /> },
            // Owner-only in the interface; the server refuses a stranger's
            // write regardless, so the page is a convenience rather than the
            // control.
            { path: "settings", element: <RepositorySettings /> },
            // Read-only for anyone; the merge control itself is the owner's,
            // and the server refuses the write regardless.
            { path: "merge", element: <MergePage /> },
            { path: "issues", element: <RepositoryIssues /> },
            // Declared before the number so "new" is never read as one; React
            // Router ranks static segments higher regardless.
            { path: "issues/new", element: <RequireAuth><NewIssue /></RequireAuth> },
            { path: "issues/:number", element: <IssueDetailPage /> },
          ],
        },
      ],
    },

    { path: "*", element: <Navigate to="/" replace /> },
  ]);

export default AppRoutes;
