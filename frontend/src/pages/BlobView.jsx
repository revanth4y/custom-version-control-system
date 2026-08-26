import { useCallback, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { Box, Heading, Text, Label } from "@primer/react";
import Octicon from "../components/common/Octicon";
import { FileBinaryIcon, FileIcon } from "@primer/octicons-react";

import PageContainer from "../components/layout/PageContainer";
import { AsyncBoundary } from "../components/common/states";
import Markdown from "../components/common/Markdown";
import BranchSelector from "../components/branch/BranchSelector";
import BlobActions from "../components/repository/BlobActions";
import LatestCommitBar from "../components/repository/LatestCommitBar";
import PathBreadcrumb from "../components/repository/PathBreadcrumb";
import SourceView from "../components/repository/SourceView";
import { useAsync } from "../hooks/useAsync";
import { useRepository } from "../hooks/useRepository";
import { contentService } from "../services/contentService";
import { extensionOf, formatBytes, toLines } from "../utils/bytes";
import { shouldLoadTree } from "../utils/repositoryState";

/** Beyond this many lines the file is shown without numbering, to keep it responsive. */
const MAX_NUMBERED_LINES = 5000;

const MARKDOWN_EXTENSIONS = new Set(["md", "markdown"]);

/**
 * Viewing a single file.
 *
 * The API decides whether content is text, and says so explicitly. Binary files
 * are described rather than printed: rendering arbitrary bytes as text produces
 * pages of replacement characters and can lock up the browser on a large file.
 */
const BlobView = () => {
  const { owner, name, head, canWrite, reloadHead } = useRepository();
  const params = useParams();
  const navigate = useNavigate();

  const refName = params.ref ? decodeURIComponent(params.ref) : head?.branch ?? "HEAD";
  const path = params["*"] ?? "";

  const blob = useAsync(
    () => contentService.blob(owner, name, { ref: refName, path }),
    [owner, name, refName, path],
  );

  /* The commit that last touched this file, taken from the listing of the
     directory it sits in.

     Not from /commits: that endpoint has no path filter — an unknown parameter
     is ignored, so asking it for "the last commit on this file" answers with
     the branch's latest commit whatever file you name. The tree already
     resolves this per path, correctly, in one request. */
  const parent = path.includes("/") ? path.slice(0, path.lastIndexOf("/")) : "";
  const loadSiblings = shouldLoadTree(head);
  const siblings = useAsync(
    () =>
      loadSiblings
        ? contentService.tree(owner, name, { ref: refName, path: parent, withLastCommit: true })
        : Promise.resolve(null),
    [owner, name, refName, parent, loadSiblings],
  );

  const lastCommit = siblings.data?.entries?.find((entry) => entry.path === path)?.lastCommit;

  const changeRef = useCallback(
    (branch) => navigate(`/${owner}/${name}/blob/${encodeURIComponent(branch)}/${path}`),
    [navigate, owner, name, path],
  );

  return (
    <PageContainer>
      <Box sx={{ display: "flex", alignItems: "flex-start", gap: 3, flexWrap: "wrap", mb: 3 }}>
        <BranchSelector
          owner={owner}
          name={name}
          currentRef={refName}
          headBranch={head?.branch}
          canWrite={canWrite}
          onRefChange={changeRef}
          onHeadChanged={reloadHead}
        />
        <Box sx={{ flex: 1, minWidth: 0, pt: 1 }}>
          <PathBreadcrumb owner={owner} name={name} refName={refName} path={path} />
        </Box>
      </Box>

      <AsyncBoundary
        loading={blob.loading}
        error={blob.error}
        onRetry={blob.reload}
        loadingLabel="Loading file"
        minHeight="220px"
      >
        {blob.data && (
          <FileContents
            file={blob.data}
            owner={owner}
            name={name}
            latestCommit={lastCommit}
          />
        )}
      </AsyncBoundary>
    </PageContainer>
  );
};

const FileContents = ({ file, owner, name, latestCommit }) => {
  const lines = file.binary ? [] : toLines(file.content);
  const isMarkdown = MARKDOWN_EXTENSIONS.has(extensionOf(file.path));
  const tooManyLines = lines.length > MAX_NUMBERED_LINES;

  /* Every text file can be read raw. For markdown that means the source behind
     the rendered document; for code it means the bytes without numbering or
     colour, which is what you want when copying a fragment out or checking
     whitespace. A binary file has no text to reveal, and inventing one would
     mean printing bytes as characters — the thing the binary state exists to
     prevent. */
  const canToggleRaw = !file.binary;
  const [raw, setRaw] = useState(false);
  const showRendered = isMarkdown && !raw;

  const isEmpty = !file.binary && file.size === 0;

  return (
    <Box
      sx={{
        bg: "canvas.subtle",
        border: "1px solid",
        borderColor: "border.default",
        borderRadius: 2,
        overflow: "hidden",
      }}
    >
      <LatestCommitBar owner={owner} name={name} commit={latestCommit} />

      <Box
        sx={{
          display: "flex",
          alignItems: "center",
          gap: 3,
          flexWrap: "wrap",
          px: 3,
          py: 2,
          borderBottom: "1px solid",
          borderColor: "border.muted",
        }}
      >
        <Octicon icon={file.binary ? FileBinaryIcon : FileIcon} sx={{ color: "fg.muted" }} />

        <Text sx={{ fontSize: 1, color: "fg.muted" }}>
          {file.binary ? "Binary" : `${lines.length} ${lines.length === 1 ? "line" : "lines"}`}
        </Text>
        <Text sx={{ fontSize: 1, color: "fg.muted" }}>{formatBytes(file.size)}</Text>

        {file.mode === "100755" && (
          <Label sx={{ color: "fg.muted", borderColor: "border.default" }}>Executable</Label>
        )}

        <Text
          sx={{ fontFamily: "mono", fontSize: 0, color: "fg.subtle", ml: "auto" }}
          title={`Object ${file.id}`}
        >
          {file.id?.slice(0, 12)}
        </Text>

        <BlobActions
          canToggleRaw={canToggleRaw}
          raw={raw}
          onToggleRaw={() => setRaw((current) => !current)}
          copyText={file.binary ? null : file.content}
        />
      </Box>

      {file.binary ? (
        <BinaryNotice file={file} />
      ) : isEmpty ? (
        <EmptyFileNotice />
      ) : showRendered ? (
        <Box sx={{ p: [3, 4] }}>
          <Markdown>{file.content}</Markdown>
        </Box>
      ) : raw ? (
        <RawSource content={file.content} />
      ) : (
        <SourceView
          path={file.path}
          lines={lines}
          numbered={!tooManyLines}
          binary={file.binary}
        />
      )}
    </Box>
  );
};

/**
 * The file's bytes, and nothing else.
 *
 * No numbering, no colour, no table — those are all things this view exists to
 * get out of the way. Selecting the pane selects exactly what is in the file,
 * which is the point of asking for it raw.
 */
const RawSource = ({ content }) => (
  <Box
    as="pre"
    sx={{
      m: 0,
      p: 3,
      bg: "canvas.subtle",
      color: "fg.default",
      fontFamily: "mono",
      fontSize: 0,
      overflowX: "auto",
      whiteSpace: "pre",
    }}
  >
    {content}
  </Box>
);

const BinaryNotice = ({ file }) => (
  <Box sx={{ p: 5, textAlign: "center" }}>
    <Octicon icon={FileBinaryIcon} size={24} sx={{ color: "fg.subtle" }} />
    <Heading as="h2" sx={{ fontSize: 2, fontWeight: 600, mt: 2, mb: 1 }}>
      This file is not text
    </Heading>
    <Text sx={{ color: "fg.muted", fontSize: 1, display: "block" }}>
      {formatBytes(file.size)} of binary content, so it cannot be shown as source.
    </Text>
  </Box>
);

/**
 * A tracked file with nothing in it.
 *
 * Worth stating rather than showing an empty pane: a blank area reads as a
 * failure to load, and the difference between "this file is empty" and "this
 * file did not arrive" is exactly what a reader needs to know.
 */
const EmptyFileNotice = () => (
  <Box sx={{ p: 5, textAlign: "center" }}>
    <Octicon icon={FileIcon} size={24} sx={{ color: "fg.subtle" }} />
    <Heading as="h2" sx={{ fontSize: 2, fontWeight: 600, mt: 2, mb: 1 }}>
      This file is empty
    </Heading>
    <Text sx={{ color: "fg.muted", fontSize: 1, display: "block" }}>
      It is tracked, but has no contents.
    </Text>
  </Box>
);

export default BlobView;
