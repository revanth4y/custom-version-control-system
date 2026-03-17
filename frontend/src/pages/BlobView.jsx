import { useCallback } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { Box, Heading, Text, Octicon, Label } from "@primer/react";
import { FileBinaryIcon, FileIcon } from "@primer/octicons-react";

import PageContainer from "../components/layout/PageContainer";
import { AsyncBoundary } from "../components/common/states";
import Markdown from "../components/common/Markdown";
import BranchSelector from "../components/branch/BranchSelector";
import PathBreadcrumb from "../components/repository/PathBreadcrumb";
import { useAsync } from "../hooks/useAsync";
import { useRepository } from "../hooks/useRepository";
import { contentService } from "../services/contentService";
import { extensionOf, formatBytes, toLines } from "../utils/bytes";

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
        {blob.data && <FileContents file={blob.data} />}
      </AsyncBoundary>
    </PageContainer>
  );
};

const FileContents = ({ file }) => {
  const lines = file.binary ? [] : toLines(file.content);
  const isMarkdown = MARKDOWN_EXTENSIONS.has(extensionOf(file.path));
  const tooManyLines = lines.length > MAX_NUMBERED_LINES;

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

        {file.mode === "100755" && <Label sx={{ color: "fg.muted", borderColor: "border.default" }}>Executable</Label>}

        <Text
          sx={{ fontFamily: "mono", fontSize: 0, color: "fg.subtle", ml: "auto" }}
          title={`Object ${file.id}`}
        >
          {file.id?.slice(0, 12)}
        </Text>
      </Box>

      {file.binary ? (
        <BinaryNotice file={file} />
      ) : isMarkdown ? (
        <Box sx={{ p: [3, 4] }}>
          <Markdown>{file.content}</Markdown>
        </Box>
      ) : (
        <SourceLines lines={lines} numbered={!tooManyLines} />
      )}
    </Box>
  );
};

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

const SourceLines = ({ lines, numbered }) => (
  <Box sx={{ overflowX: "auto", bg: "canvas.inset" }}>
    <Box as="table" sx={{ borderCollapse: "collapse", width: "100%", fontFamily: "mono", fontSize: 0 }}>
      <Box as="tbody">
        {lines.map((line, index) => (
          <Box as="tr" key={index} sx={{ "&:hover": { bg: "canvas.subtle" } }}>
            {numbered && (
              <Box
                as="td"
                sx={{
                  // The gutter must not be selectable, or copying a snippet
                  // takes the line numbers with it.
                  userSelect: "none",
                  textAlign: "right",
                  color: "fg.subtle",
                  px: 3,
                  width: "1%",
                  whiteSpace: "nowrap",
                  verticalAlign: "top",
                  borderRight: "1px solid",
                  borderColor: "border.muted",
                }}
              >
                {index + 1}
              </Box>
            )}
            <Box
              as="td"
              sx={{ px: 3, whiteSpace: "pre", color: "fg.default", verticalAlign: "top" }}
            >
              {line === "" ? " " : line}
            </Box>
          </Box>
        ))}
      </Box>
    </Box>
  </Box>
);

export default BlobView;
