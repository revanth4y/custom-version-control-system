import { Link as RouterLink, useLocation } from "react-router-dom";
import { Box, Text, Octicon } from "@primer/react";
import {
  CodeIcon,
  GitCommitIcon,
  GitBranchIcon,
  GraphIcon,
  IssueOpenedIcon,
} from "@primer/octicons-react";

/**
 * The repository tabs.
 *
 * Built from links rather than Primer's UnderlineNav. That component measures
 * its items to decide which to fold into an overflow menu, and at narrow widths
 * it read a null ref during a route change and threw - taking the whole page
 * down, because tapping a tab on a phone is exactly when it remeasures.
 *
 * These are ordinary anchors that simply scroll sideways when they do not fit.
 * Nothing is measured, so there is nothing to get wrong, and a tab is a real
 * link: middle-click and open-in-new-tab work, which they did not before.
 */
const TABS = [
  { key: "code", label: "Code", icon: CodeIcon, path: "" },
  { key: "commits", label: "Commits", icon: GitCommitIcon, path: "commits" },
  { key: "branches", label: "Branches", icon: GitBranchIcon, path: "branches" },
  { key: "issues", label: "Issues", icon: IssueOpenedIcon, path: "issues" },
  { key: "insights", label: "Insights", icon: GraphIcon, path: "insights" },
];

/**
 * Sections that belong to a tab without being named after it: a single commit
 * and a comparison are places you arrive at from the history, and merging is
 * reached from the branches.
 */
const SECTION_TAB = {
  "": "code",
  tree: "code",
  blob: "code",
  commit: "commits",
  compare: "commits",
  merge: "branches",
};

const RepoNav = ({ owner, name }) => {
  const location = useLocation();

  const base = `/${owner}/${name}`;
  const rest = location.pathname.slice(base.length).replace(/^\//, "");
  const section = rest.split("/")[0] ?? "";
  const active = TABS.find((tab) => tab.path && tab.path === section)?.key ?? SECTION_TAB[section] ?? "code";

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
        <Box
          as="nav"
          aria-label="Repository"
          sx={{
            display: "flex",
            gap: 1,
            // Scrolls rather than folding into a menu; the scrollbar itself is
            // hidden because the tabs are short and the overflow is obvious.
            overflowX: "auto",
            scrollbarWidth: "none",
            "&::-webkit-scrollbar": { display: "none" },
          }}
        >
          {TABS.map((tab) => {
            const current = tab.key === active;
            return (
              <Box
                key={tab.key}
                as={RouterLink}
                to={tab.path ? `${base}/${tab.path}` : base}
                aria-current={current ? "page" : undefined}
                sx={{
                  display: "inline-flex",
                  alignItems: "center",
                  gap: 2,
                  flexShrink: 0,
                  px: 3,
                  py: 3,
                  textDecoration: "none",
                  color: current ? "fg.default" : "fg.muted",
                  fontWeight: current ? 600 : 400,
                  // The underline is drawn on the element itself so the active
                  // tab keeps its indicator wherever the row is scrolled to.
                  borderBottom: "2px solid",
                  borderColor: current ? "accent.emphasis" : "transparent",
                  "&:hover": { color: "fg.default" },
                }}
              >
                <Octicon icon={tab.icon} size={16} sx={{ color: current ? "fg.default" : "fg.subtle" }} />
                <Text sx={{ fontSize: 1, whiteSpace: "nowrap" }}>{tab.label}</Text>
              </Box>
            );
          })}
        </Box>
      </Box>
    </Box>
  );
};

export default RepoNav;
