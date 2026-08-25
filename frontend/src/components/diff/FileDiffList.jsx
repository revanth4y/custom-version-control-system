import { Box, Text } from "@primer/react";
import Octicon from "../common/Octicon";
import { DiffAddedIcon, DiffModifiedIcon, DiffRemovedIcon } from "@primer/octicons-react";

import { FileState, anchorFor, fileState, splitPath } from "../../utils/diff";

const STATUS_ICON = {
  ADDED: { icon: DiffAddedIcon, color: "success.fg" },
  DELETED: { icon: DiffRemovedIcon, color: "danger.fg" },
  MODIFIED: { icon: DiffModifiedIcon, color: "attention.fg" },
};

const NOTE = {
  [FileState.BINARY]: "binary",
  [FileState.TOO_LARGE]: "too large",
  [FileState.MODE_ONLY]: "mode only",
};

/**
 * The list of changed files, as a way in to each one's diff.
 *
 * Selecting a file sets the URL fragment and scrolls to its section, so the
 * choice survives a reload and can be sent to someone else.
 */
const FileDiffList = ({ files, activePath, onSelect }) => (
  <Box
    sx={{
      border: "1px solid",
      borderColor: "border.default",
      borderRadius: 2,
      bg: "canvas.subtle",
      overflow: "hidden",
    }}
  >
    <Box
      sx={{
        px: 3,
        py: 2,
        borderBottom: "1px solid",
        borderColor: "border.muted",
        bg: "canvas.overlay",
      }}
    >
      <Text sx={{ fontSize: 0, fontWeight: 600, color: "fg.muted" }}>
        {files.length} changed {files.length === 1 ? "file" : "files"}
      </Text>
    </Box>

    <Box sx={{ maxHeight: ["220px", "220px", "60vh"], overflowY: "auto" }}>
      {files.map((file) => {
        const status = STATUS_ICON[file.status] ?? STATUS_ICON.MODIFIED;
        const { directory, name } = splitPath(file.path);
        const note = NOTE[fileState(file)];
        const active = file.path === activePath;

        return (
          <Box
            as="button"
            type="button"
            key={file.path}
            onClick={() => onSelect(file.path)}
            aria-current={active ? "true" : undefined}
            title={file.path}
            sx={{
              display: "flex",
              alignItems: "center",
              gap: 2,
              width: "100%",
              textAlign: "left",
              bg: active ? "neutral.subtle" : "transparent",
              border: "none",
              borderLeft: "2px solid",
              borderColor: active ? "accent.emphasis" : "transparent",
              px: 3,
              py: 2,
              cursor: "pointer",
              color: "fg.default",
              "&:hover": { bg: "canvas.inset" },
            }}
          >
            <Octicon icon={status.icon} size={14} sx={{ color: status.color, flexShrink: 0 }} />

            {/* The filename identifies the row, so it comes first and never
                clips; the directory follows as context and is the part allowed
                to be cut. Putting the directory first and truncating its start
                needed direction: rtl, which reorders the text itself and
                rendered "src/" as "/src". */}
            <Box sx={{ minWidth: 0, flex: 1, display: "flex", alignItems: "baseline", gap: 2 }}>
              <Text
                sx={{ fontFamily: "mono", fontSize: 0, color: "fg.default", whiteSpace: "nowrap", flexShrink: 0 }}
              >
                {name}
              </Text>
              {directory && (
                <Text
                  sx={{
                    fontFamily: "mono",
                    fontSize: 0,
                    color: "fg.subtle",
                    overflow: "hidden",
                    textOverflow: "ellipsis",
                    whiteSpace: "nowrap",
                    minWidth: 0,
                  }}
                >
                  {directory.replace(/\/$/, "")}
                </Text>
              )}
            </Box>

            {note && (
              <Text sx={{ fontSize: 0, color: "fg.subtle", flexShrink: 0 }}>{note}</Text>
            )}

            <Box sx={{ display: "flex", gap: 1, flexShrink: 0, fontSize: 0 }}>
              {file.additions > 0 && <Text sx={{ color: "success.fg" }}>+{file.additions}</Text>}
              {file.deletions > 0 && <Text sx={{ color: "danger.fg" }}>-{file.deletions}</Text>}
            </Box>
          </Box>
        );
      })}
    </Box>
  </Box>
);

export { anchorFor };
export default FileDiffList;
