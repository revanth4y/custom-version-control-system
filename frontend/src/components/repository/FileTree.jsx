import { Link as RouterLink } from "react-router-dom";
import { Box, Link, Text, Octicon } from "@primer/react";
import { FileIcon, FileDirectoryFillIcon, FileSymlinkFileIcon } from "@primer/octicons-react";

import { EmptyState } from "../common/states";

/**
 * A directory listing.
 *
 * Directories are shown before files, which is how a listing is usually read:
 * you are either descending or you have arrived. Within each group the server's
 * canonical order is preserved, so what is shown matches what the tree object
 * actually contains.
 */
const FileTree = ({ owner, name, refName, entries, parentPath }) => {
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

  const sorted = [...entries].sort((a, b) => {
    if (a.type !== b.type) return a.type === "dir" ? -1 : 1;
    return 0;
  });

  return (
    <Box role="table" aria-label="Files">
      {sorted.map((entry, index) => (
        <Box
          key={entry.path}
          role="row"
          sx={{
            display: "flex",
            alignItems: "center",
            gap: 2,
            px: 3,
            py: 2,
            borderTop: index === 0 ? "none" : "1px solid",
            borderColor: "border.muted",
            "&:hover": { bg: "canvas.overlay" },
          }}
        >
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
      ))}
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
