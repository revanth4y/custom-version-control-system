import { useState } from "react";
import { useParams } from "react-router-dom";
import { Box, Button, Heading, Label, Text } from "@primer/react";
import { PencilIcon, TagIcon } from "@primer/octicons-react";

import Octicon from "../components/common/Octicon";
import PageContainer from "../components/layout/PageContainer";
import { AsyncBoundary } from "../components/common/states";
import Notice from "../components/common/Notice";
import EditReleaseDialog from "../components/release/EditReleaseDialog";
import { useAsync } from "../hooks/useAsync";
import { useRepository } from "../hooks/useRepository";
import { releaseService } from "../services/releaseService";

/**
 * One release, its notes, and the tag it names.
 *
 * The tag is shown as the name the release actually stores rather than resolved
 * to a commit here: what that name currently points at is the tags page's
 * question, and answering it in two places would let the two disagree.
 */
const ReleaseDetail = () => {
  const { owner, name, canWrite } = useRepository();
  const { releaseId } = useParams();

  const [editing, setEditing] = useState(false);
  const [notice, setNotice] = useState(null);

  const query = useAsync(
    () => releaseService.get(owner, name, releaseId),
    [owner, name, releaseId],
  );
  const release = query.data;

  return (
    <PageContainer>
      <AsyncBoundary
        loading={query.loading}
        error={query.error}
        errorTitle="This release could not be loaded"
        onRetry={query.reload}
        loadingLabel="Loading release"
      >
        {release && (
          <>
            <Box
              sx={{
                display: "flex",
                alignItems: "flex-start",
                justifyContent: "space-between",
                gap: 3,
                flexWrap: "wrap",
                mb: 3,
              }}
            >
              <Box sx={{ minWidth: 0 }}>
                <Box sx={{ display: "flex", alignItems: "center", gap: 2, flexWrap: "wrap" }}>
                  <Heading as="h1" sx={{ fontSize: 3, fontWeight: 600, color: "fg.default" }}>
                    {release.name}
                  </Heading>
                  {release.draft && <Label variant="secondary">Draft</Label>}
                  {release.prerelease && <Label variant="attention">Pre-release</Label>}
                </Box>

                <Box sx={{ display: "flex", alignItems: "center", gap: 1, mt: 1 }}>
                  <Octicon icon={TagIcon} size={14} sx={{ color: "fg.muted" }} />
                  <Text sx={{ fontFamily: "mono", fontSize: 0, color: "fg.muted" }}>
                    {release.tag}
                  </Text>
                  {release.authorName && (
                    <Text sx={{ fontSize: 0, color: "fg.muted" }}>
                      {" · by "}
                      {release.authorName}
                    </Text>
                  )}
                </Box>
              </Box>

              {canWrite && (
                <Button leadingVisual={PencilIcon} onClick={() => setEditing(true)}>
                  Edit
                </Button>
              )}
            </Box>

            {notice && (
              <Box sx={{ mb: 3 }}>
                <Notice variant={notice.variant}>{notice.text}</Notice>
              </Box>
            )}

            {release.draft && (
              <Box sx={{ mb: 3 }}>
                <Notice variant="info">
                  This release is a draft. Only you can see it until it is published.
                </Notice>
              </Box>
            )}

            <Box
              sx={{
                border: "1px solid",
                borderColor: "border.default",
                borderRadius: 2,
                p: 3,
                bg: "canvas.default",
              }}
            >
              {release.body ? (
                <Text
                  sx={{
                    color: "fg.default",
                    fontSize: 1,
                    whiteSpace: "pre-wrap",
                    wordBreak: "break-word",
                  }}
                >
                  {release.body}
                </Text>
              ) : (
                <Text sx={{ color: "fg.muted", fontSize: 1 }}>This release has no notes.</Text>
              )}
            </Box>

            {editing && (
              <EditReleaseDialog
                owner={owner}
                name={name}
                release={release}
                onClose={() => setEditing(false)}
                onSaved={() => {
                  setEditing(false);
                  query.reload();
                  setNotice({ variant: "success", text: "The release was updated." });
                }}
              />
            )}
          </>
        )}
      </AsyncBoundary>
    </PageContainer>
  );
};

export default ReleaseDetail;
