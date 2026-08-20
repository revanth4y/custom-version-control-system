import { Box, Label, Text, Octicon } from "@primer/react";
import { FileBinaryIcon, FileDiffIcon, LawIcon } from "@primer/octicons-react";

import Hunk from "./Hunk";
import { FileState, anchorFor, fileState, modeChange, splitPath } from "../../utils/diff";
import { formatBytes } from "../../utils/bytes";

const STATUS_TONE = {
  ADDED: { label: "added", fg: "success.fg", border: "success.muted" },
  DELETED: { label: "deleted", fg: "danger.fg", border: "danger.muted" },
  MODIFIED: { label: "modified", fg: "attention.fg", border: "attention.muted" },
};

/**
 * One file's diff: a header describing the change, then whatever body suits it.
 *
 * Four bodies are possible and only one of them is lines. A file with no hunks
 * is not necessarily unchanged - it may be binary, too large to diff, or a
 * mode-only change - so the body is chosen from the engine's flags rather than
 * from whether there is anything to draw.
 */
const FileDiff = ({ file }) => {
  const state = fileState(file);
  const mode = modeChange(file);
  const { directory, name } = splitPath(file.path);
  const tone = STATUS_TONE[file.status] ?? STATUS_TONE.MODIFIED;

  return (
    <Box
      id={anchorFor(file.path)}
      sx={{
        border: "1px solid",
        borderColor: "border.default",
        borderRadius: 2,
        bg: "canvas.subtle",
        overflow: "hidden",
        // Anchored navigation should not tuck the header under the sticky page
        // header when jumping to a file.
        scrollMarginTop: "72px",
      }}
    >
      <Box
        sx={{
          display: "flex",
          alignItems: "center",
          gap: 2,
          flexWrap: "wrap",
          px: 3,
          py: 2,
          borderBottom: "1px solid",
          borderColor: "border.muted",
          bg: "canvas.overlay",
        }}
      >
        <Octicon
          icon={state === FileState.BINARY ? FileBinaryIcon : FileDiffIcon}
          sx={{ color: "fg.muted", flexShrink: 0 }}
        />

        <Text sx={{ fontFamily: "mono", fontSize: 0, minWidth: 0, overflowWrap: "anywhere" }}>
          <Text as="span" sx={{ color: "fg.muted" }}>
            {directory}
          </Text>
          <Text as="span" sx={{ color: "fg.default", fontWeight: 600 }}>
            {name}
          </Text>
        </Text>

        <Label sx={{ color: tone.fg, borderColor: tone.border, flexShrink: 0 }}>{tone.label}</Label>

        {mode && (
          <Label
            sx={{ color: "fg.muted", borderColor: "border.default", flexShrink: 0 }}
            title={`${mode.from} to ${mode.to}`}
          >
            {mode.label}
          </Label>
        )}

        <Box sx={{ ml: "auto", display: "flex", gap: 2, flexShrink: 0, fontSize: 0 }}>
          {file.additions > 0 && <Text sx={{ color: "success.fg" }}>+{file.additions}</Text>}
          {file.deletions > 0 && <Text sx={{ color: "danger.fg" }}>-{file.deletions}</Text>}
        </Box>
      </Box>

      <FileBody file={file} state={state} mode={mode} />
    </Box>
  );
};

const FileBody = ({ file, state, mode }) => {
  if (state === FileState.TEXT) {
    return (
      // One scroller for the whole file, so every row scrolls together and the
      // columns stay aligned. The page itself never widens.
      <Box sx={{ overflowX: "auto", bg: "canvas.subtle" }}>
        <Box
          as="table"
          sx={{
            // Separate, not collapse: Chrome does not honour position: sticky on
            // a cell in a collapsed table, so the line-number gutters scrolled
            // away with the code instead of staying put.
            borderCollapse: "separate",
            borderSpacing: 0,
            fontFamily: "mono",
            fontSize: 0,
            lineHeight: 1.55,
            width: "max-content",
            minWidth: "100%",
          }}
        >
          <Box as="tbody">
            {file.hunks.map((hunk) => (
              <Hunk key={hunk.header} hunk={hunk} />
            ))}
          </Box>
        </Box>
      </Box>
    );
  }

  if (state === FileState.BINARY) {
    return (
      <Explanation
        icon={FileBinaryIcon}
        title="Binary file"
        detail={
          <>
            {file.status === "ADDED" && "Added. "}
            {file.status === "DELETED" && "Removed. "}
            {file.status === "MODIFIED" && "Changed. "}
            The contents are not text, so there are no lines to compare.
            <BlobIds file={file} />
          </>
        }
      />
    );
  }

  if (state === FileState.TOO_LARGE) {
    return (
      <Explanation
        icon={FileDiffIcon}
        title="Diff too large to render"
        detail={
          <>
            The engine declined to compare this file line by line to bound the work. Its status and
            identity are still known.
            <BlobIds file={file} />
          </>
        }
      />
    );
  }

  if (state === FileState.MODE_ONLY) {
    return (
      <Explanation
        icon={LawIcon}
        title="Mode changed"
        detail={
          <>
            The file was {mode.label}; its contents are identical.
            <BlobIds file={file} />
          </>
        }
      />
    );
  }

  return <Explanation icon={FileDiffIcon} title="No changes" detail="This file is unchanged." />;
};

/**
 * What is known about a file whose contents cannot be shown as lines.
 *
 * The blob ids identify exactly which two objects differ, and the sizes are the
 * only measure of the change itself - the engine reports them because it has
 * both blobs in hand while diffing, so nothing has to be downloaded to count.
 */
const BlobIds = ({ file }) => {
  if (!file.oldBlob && !file.newBlob) return null;

  const side = (blob, size, present) => (
    <Text as="span" sx={{ color: present ? "fg.muted" : "fg.subtle" }}>
      {present ? `${blob.slice(0, 12)} · ${formatBytes(size)}` : "none"}
    </Text>
  );

  return (
    <Text as="span" sx={{ display: "block", mt: 2, fontFamily: "mono", fontSize: 0 }}>
      {side(file.oldBlob, file.oldSize, Boolean(file.oldBlob))}
      <Text as="span" sx={{ color: "fg.subtle", mx: 2 }}>→</Text>
      {side(file.newBlob, file.newSize, Boolean(file.newBlob))}
    </Text>
  );
};

const Explanation = ({ icon, title, detail }) => (
  <Box sx={{ px: 3, py: 4, textAlign: "center" }}>
    <Octicon icon={icon} size={20} sx={{ color: "fg.subtle" }} />
    <Text sx={{ display: "block", fontWeight: 600, fontSize: 1, mt: 2 }}>{title}</Text>
    <Text sx={{ display: "block", color: "fg.muted", fontSize: 0, mt: 1, maxWidth: "60ch", mx: "auto" }}>
      {detail}
    </Text>
  </Box>
);

export default FileDiff;
