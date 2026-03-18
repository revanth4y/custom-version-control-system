import { useCallback, useEffect, useState } from "react";
import { Box, Text, Octicon } from "@primer/react";
import { FileDiffIcon } from "@primer/octicons-react";

import FileDiff from "./FileDiff";
import FileDiffList from "./FileDiffList";
import { anchorFor, pathFromAnchor, summarise } from "../../utils/diff";

/**
 * A whole diff: the list of files, and each file's diff beneath it.
 *
 * The selected file lives in the URL fragment so a link to one file's diff can
 * be shared and survives a reload. On a wide screen the list sits beside the
 * diffs; below that it stacks above them, where it still works as a table of
 * contents.
 */
const DiffViewer = ({ result }) => {
  const files = result?.files ?? [];
  const counts = summarise(files);
  const [activePath, setActivePath] = useState(() => pathFromAnchor(window.location.hash.slice(1)));

  const select = useCallback((path) => {
    setActivePath(path);
    const anchor = anchorFor(path);
    // Replace rather than push: choosing files should not fill the back stack.
    window.history.replaceState(null, "", `#${anchor}`);
    document.getElementById(anchor)?.scrollIntoView({ behavior: "smooth", block: "start" });
  }, []);

  // A link arriving with a fragment should land on that file.
  useEffect(() => {
    const path = pathFromAnchor(window.location.hash.slice(1));
    if (!path || !files.some((file) => file.path === path)) return;
    setActivePath(path);
    const timer = setTimeout(
      () => document.getElementById(anchorFor(path))?.scrollIntoView({ block: "start" }),
      0,
    );
    return () => clearTimeout(timer);
  }, [files]);

  if (files.length === 0) {
    return (
      <Box
        sx={{
          border: "1px solid",
          borderColor: "border.default",
          borderRadius: 2,
          bg: "canvas.subtle",
          px: 3,
          py: 5,
          textAlign: "center",
        }}
      >
        <Octicon icon={FileDiffIcon} size={20} sx={{ color: "fg.subtle" }} />
        <Text sx={{ display: "block", fontWeight: 600, fontSize: 1, mt: 2 }}>No changes</Text>
        <Text sx={{ display: "block", color: "fg.muted", fontSize: 0, mt: 1 }}>
          These two revisions have identical contents.
        </Text>
      </Box>
    );
  }

  return (
    <Box>
      <Box sx={{ display: "flex", gap: 2, mb: 3, flexWrap: "wrap", fontSize: 0 }}>
        <Text sx={{ color: "fg.muted" }}>
          {counts.files} changed {counts.files === 1 ? "file" : "files"}
        </Text>
        {counts.additions > 0 && (
          <Text sx={{ color: "success.fg" }}>+{counts.additions}</Text>
        )}
        {counts.deletions > 0 && <Text sx={{ color: "danger.fg" }}>-{counts.deletions}</Text>}
      </Box>

      <Box
        sx={{
          display: "grid",
          // Side by side only where there is room for both; below that the file
          // list becomes a table of contents above the diffs.
          gridTemplateColumns: ["1fr", "1fr", "minmax(0, 300px) minmax(0, 1fr)"],
          gap: 3,
          alignItems: "start",
        }}
      >
        <Box sx={{ position: ["static", "static", "sticky"], top: "72px", minWidth: 0 }}>
          <FileDiffList files={files} activePath={activePath} onSelect={select} />
        </Box>

        <Box sx={{ display: "flex", flexDirection: "column", gap: 3, minWidth: 0 }}>
          {files.map((file) => (
            <FileDiff key={file.path} file={file} />
          ))}
        </Box>
      </Box>
    </Box>
  );
};

export default DiffViewer;
