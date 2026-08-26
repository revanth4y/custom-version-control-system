import RouterLink from "../common/RouterLink";
import { Box, Link, Text } from "@primer/react";
import Octicon from "../common/Octicon";
import { FileDirectoryFillIcon, FileIcon, FileSymlinkFileIcon } from "@primer/octicons-react";

import { EmptyState } from "../common/states";
import { formatRelativeTime } from "../../utils/dates";
import {
  commitSubject,
  hasLastCommit,
  lastCommitPath,
  showsCommitColumns,
  sortEntries,
} from "../../utils/treeEntries";

/**
 * A directory listing: what is here, and what last happened to it.
 *
 * The reference rules this as three columns — name, the commit that last
 * touched the path, and when. That last pair comes from one request, not one
 * per file: the server walks history once for the whole listing and attaches
 * the result to each entry.
 *
 * A path whose commit could not be resolved within the server's window carries
 * no `lastCommit` at all, so each row is written to render without one. If no
 * row resolved a commit, the columns are dropped entirely rather than ruled off
 * as two empty ones.
 *
 * Below the wide breakpoint only the name survives. Three columns in 390px
 * leaves a commit subject about eight characters wide, which is worse than not
 * showing it.
 */
const FileTree = ({ owner, name, refName, entries }) => {
  if (entries.length === 0) {
    return (
      <EmptyState
        icon={FileDirectoryFillIcon}
        title="This directory is empty"
        message="Nothing is tracked at this path."
        minHeight="180px"
      />
    );
  }

  const sorted = sortEntries(entries);
  const withCommits = showsCommitColumns(sorted);

  return (
    <Box role="table" aria-label="Files">
      {withCommits && (
        <Box
          role="row"
          sx={{
            display: ["none", "none", "flex"],
            alignItems: "center",
            gap: 3,
            px: 3,
            py: 2,
            bg: "canvas.inset",
            borderBottom: "1px solid",
            borderColor: "border.muted",
          }}
        >
          <Box role="columnheader" sx={{ flex: 1, minWidth: 0 }}>
            <Text sx={{ fontSize: 0, color: "fg.muted", fontWeight: 600 }}>Name</Text>
          </Box>
          <Box role="columnheader" sx={{ flex: 1, minWidth: 0 }}>
            <Text sx={{ fontSize: 0, color: "fg.muted", fontWeight: 600 }}>Last commit</Text>
          </Box>
          <Box role="columnheader" sx={{ width: "120px", flexShrink: 0, textAlign: "right" }}>
            <Text sx={{ fontSize: 0, color: "fg.muted", fontWeight: 600 }}>Last update</Text>
          </Box>
        </Box>
      )}

      {sorted.map((entry, index) => {
        const subject = hasLastCommit(entry) ? commitSubject(entry.lastCommit.message) : null;
        const commitHref = lastCommitPath(owner, name, entry);

        return (
          <Box
            key={entry.path}
            role="row"
            sx={{
              display: "flex",
              alignItems: "center",
              gap: 3,
              px: 3,
              py: 2,
              borderTop: index === 0 && !withCommits ? "none" : "1px solid",
              borderColor: "border.muted",
              "&:hover": { bg: "canvas.overlay" },
            }}
          >
            <Box role="cell" sx={{ display: "flex", alignItems: "center", gap: 2, flex: 1, minWidth: 0 }}>
              <Octicon
                icon={iconFor(entry)}
                sx={{ color: entry.type === "dir" ? "accent.fg" : "fg.muted", flexShrink: 0 }}
              />

              <Link
                as={RouterLink}
                to={destination(owner, name, refName, entry)}
                sx={{
                  fontSize: 1,
                  color: "fg.default",
                  minWidth: 0,
                  wordBreak: "break-all",
                  "&:hover": { color: "accent.fg", textDecoration: "underline" },
                }}
              >
                {entry.name}
              </Link>

              {/* The executable bit is the only mode worth surfacing in a listing;
                  the rest is noise unless you are looking at the object itself. */}
              {entry.mode === "100755" && (
                <Text sx={{ fontSize: 0, color: "fg.subtle", fontFamily: "mono", flexShrink: 0 }}>
                  exec
                </Text>
              )}
            </Box>

            {withCommits && (
              <>
                <Box
                  role="cell"
                  sx={{ display: ["none", "none", "block"], flex: 1, minWidth: 0 }}
                >
                  {subject && commitHref ? (
                    <Link
                      as={RouterLink}
                      to={commitHref}
                      title={subject}
                      sx={{
                        fontSize: 0,
                        color: "fg.muted",
                        display: "block",
                        overflow: "hidden",
                        textOverflow: "ellipsis",
                        whiteSpace: "nowrap",
                        "&:hover": { color: "accent.fg", textDecoration: "underline" },
                      }}
                    >
                      {subject}
                    </Link>
                  ) : (
                    <Text sx={{ fontSize: 0, color: "fg.subtle" }}>—</Text>
                  )}
                </Box>

                <Box
                  role="cell"
                  sx={{
                    display: ["none", "none", "block"],
                    width: "120px",
                    flexShrink: 0,
                    textAlign: "right",
                  }}
                >
                  <Text sx={{ fontSize: 0, color: "fg.subtle", whiteSpace: "nowrap" }}>
                    {hasLastCommit(entry) ? formatRelativeTime(entry.lastCommit.timestamp) : "—"}
                  </Text>
                </Box>
              </>
            )}
          </Box>
        );
      })}
    </Box>
  );
};

function iconFor(entry) {
  if (entry.type === "dir") return FileDirectoryFillIcon;
  return entry.mode === "100755" ? FileSymlinkFileIcon : FileIcon;
}

function destination(owner, name, refName, entry) {
  const kind = entry.type === "dir" ? "tree" : "blob";
  return `/${owner}/${name}/${kind}/${encodeURIComponent(refName)}/${entry.path}`;
}

export default FileTree;
