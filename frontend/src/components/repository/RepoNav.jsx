import { useLocation, useNavigate } from "react-router-dom";
import { Box, UnderlineNav } from "@primer/react";
import {
  CodeIcon,
  GitCommitIcon,
  GitBranchIcon,
  IssueOpenedIcon,
  GraphIcon,
} from "@primer/octicons-react";

/**
 * The repository tabs.
 *
 * Only Code exists yet; the rest are declared here so the navigation is complete
 * from the outset and later phases add pages rather than rearranging the shell.
 * Tabs without a page are disabled rather than hidden, because a tab that
 * appears later moves everything beside it and makes the interface feel
 * unsettled.
 */
const TABS = [
  { key: "code", label: "Code", icon: CodeIcon, path: "", ready: true },
  { key: "commits", label: "Commits", icon: GitCommitIcon, path: "commits", ready: true },
  { key: "branches", label: "Branches", icon: GitBranchIcon, path: "branches", ready: true },
  { key: "issues", label: "Issues", icon: IssueOpenedIcon, path: "issues", ready: false },
  { key: "insights", label: "Insights", icon: GraphIcon, path: "insights", ready: false },
];

const RepoNav = ({ owner, name }) => {
  const navigate = useNavigate();
  const location = useLocation();

  const base = `/${owner}/${name}`;
  const rest = location.pathname.slice(base.length).replace(/^\//, "");
  const section = rest.split("/")[0] ?? "";

  // Sections that belong to a tab without being named after it: a single
  // commit and a comparison are both places you arrive at from the history, so
  // Commits stays lit rather than the tab falling back to Code.
  const SECTION_TAB = { "": "code", tree: "code", blob: "code", commit: "commits", compare: "commits", merge: "branches" };

  const active =
    TABS.find((tab) => tab.path && tab.path === section)?.key ?? SECTION_TAB[section] ?? "code";

  return (
    <Box
      sx={{
        bg: "canvas.subtle",
        borderBottom: "1px solid",
        borderColor: "border.default",
        px: [3, 3, 4],
      }}
    >
      <Box sx={{ maxWidth: "1280px", mx: "auto" }}>
        <UnderlineNav aria-label="Repository">
          {TABS.map((tab) => (
            <UnderlineNav.Item
              key={tab.key}
              icon={tab.icon}
              aria-current={tab.key === active ? "page" : undefined}
              // Not-yet-built sections are inert rather than navigating to a
              // page that does not exist.
              sx={tab.ready ? undefined : { opacity: 0.45, cursor: "not-allowed" }}
              onSelect={(event) => {
                event.preventDefault();
                if (!tab.ready) return;
                navigate(tab.path ? `${base}/${tab.path}` : base);
              }}
            >
              {tab.label}
            </UnderlineNav.Item>
          ))}
        </UnderlineNav>
      </Box>
    </Box>
  );
};

export default RepoNav;
