import { useState } from "react";
import { Box, Button, Heading, Label, Text, Link } from "@primer/react";
import { PlusIcon, TagIcon, TrashIcon } from "@primer/octicons-react";

import Octicon from "../components/common/Octicon";
import RouterLink from "../components/common/RouterLink";
import PageContainer from "../components/layout/PageContainer";
import { AsyncBoundary, EmptyState } from "../components/common/states";
import Notice from "../components/common/Notice";
import CreateReleaseDialog from "../components/release/CreateReleaseDialog";
import CreateTagDialog from "../components/release/CreateTagDialog";
import ConfirmDeleteDialog from "../components/release/ConfirmDeleteDialog";
import { useReleases, useTags } from "../hooks/useReleases";
import { useMutation } from "../hooks/useMutation";
import { useRepository } from "../hooks/useRepository";
import { releaseService } from "../services/releaseService";

const TABS = [
  { key: "releases", label: "Releases" },
  { key: "tags", label: "Tags" },
];

/**
 * Releases and tags on one surface.
 *
 * They share a page rather than taking a tab each because they are two views of
 * the same thing: a release is a note about a tag, and a tag with no release is
 * still worth seeing. The repository navigation already carries eight tabs, and
 * a ninth for something this closely related would cost more than it explains.
 */
const Releases = () => {
  const { owner, name, canWrite } = useRepository();

  const [view, setView] = useState("releases");
  const [creatingRelease, setCreatingRelease] = useState(false);
  const [creatingTag, setCreatingTag] = useState(false);
  const [deleting, setDeleting] = useState(null);
  const [notice, setNotice] = useState(null);

  const releases = useReleases(owner, name);
  const tags = useTags(owner, name);
  const { run } = useMutation();

  const showingReleases = view === "releases";

  const afterChange = (text) => {
    releases.reload();
    tags.reload();
    setNotice({ variant: "success", text });
  };

  const confirmDelete = async () => {
    const target = deleting;
    if (!target) return;

    const result =
      target.kind === "release"
        ? await run(
            () => releaseService.remove(owner, name, target.id),
            "The release could not be deleted.",
          )
        : await run(
            () => releaseService.removeTag(owner, name, target.name),
            "The tag could not be deleted.",
          );

    setDeleting(null);
    if (result.ok) {
      afterChange(
        target.kind === "release"
          ? `Release ${target.name} was deleted. Its tag was left in place.`
          : `Tag ${target.name} was deleted.`,
      );
    } else {
      setNotice({ variant: "danger", text: result.error });
    }
  };

  return (
    <PageContainer>
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
        <Box>
          <Heading as="h1" sx={{ fontSize: 3, fontWeight: 600, color: "fg.default" }}>
            Releases
          </Heading>
          <Text sx={{ fontSize: 1, color: "fg.muted" }}>
            Published notes, and the tags they name.
          </Text>
        </Box>

        {canWrite && (
          <Box sx={{ display: "flex", gap: 2, flexWrap: "wrap" }}>
            <Button leadingVisual={TagIcon} onClick={() => setCreatingTag(true)}>
              New tag
            </Button>
            <Button variant="primary" leadingVisual={PlusIcon} onClick={() => setCreatingRelease(true)}>
              Draft a release
            </Button>
          </Box>
        )}
      </Box>

      {notice && (
        <Box sx={{ mb: 3 }}>
          <Notice variant={notice.variant}>{notice.text}</Notice>
        </Box>
      )}

      <Box
        role="tablist"
        aria-label="Releases and tags"
        sx={{ display: "flex", gap: 1, mb: 3, borderBottom: "1px solid", borderColor: "border.default" }}
      >
        {TABS.map((tab) => {
          const active = view === tab.key;
          return (
            <Box
              key={tab.key}
              as="button"
              type="button"
              role="tab"
              aria-selected={active}
              onClick={() => setView(tab.key)}
              sx={{
                appearance: "none",
                background: "transparent",
                border: 0,
                borderBottom: "2px solid",
                borderColor: active ? "accent.emphasis" : "transparent",
                color: active ? "fg.default" : "fg.muted",
                cursor: "pointer",
                fontSize: 1,
                fontWeight: active ? 600 : 400,
                px: 3,
                py: 2,
              }}
            >
              {tab.label}
            </Box>
          );
        })}
      </Box>

      {showingReleases ? (
        <AsyncBoundary
          loading={releases.loading}
          error={releases.error}
          errorTitle="Releases could not be loaded"
          onRetry={releases.reload}
          loadingLabel="Loading releases"
          isEmpty={releases.loaded && releases.releases.length === 0}
          empty={
            <EmptyState
              icon={TagIcon}
              title="No releases yet"
              message={
                canWrite
                  ? "Tag a commit, then publish a release describing it."
                  : "Nothing has been released from this repository."
              }
            />
          }
        >
          <Box sx={{ display: "flex", flexDirection: "column", gap: 3 }}>
            {releases.releases.map((release) => (
              <ReleaseRow
                key={release.id}
                owner={owner}
                name={name}
                release={release}
                canWrite={canWrite}
                onDelete={() =>
                  setDeleting({ kind: "release", id: release.id, name: release.name })
                }
              />
            ))}
          </Box>
        </AsyncBoundary>
      ) : (
        <AsyncBoundary
          loading={tags.loading}
          error={tags.error}
          errorTitle="Tags could not be loaded"
          onRetry={tags.reload}
          loadingLabel="Loading tags"
          isEmpty={tags.loaded && tags.tags.length === 0}
          empty={
            <EmptyState
              icon={TagIcon}
              title="No tags yet"
              message={
                canWrite
                  ? "A tag marks a point in history permanently."
                  : "This repository has no tags."
              }
            />
          }
        >
          <Box sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
            {tags.tags.map((tag) => (
              <TagRow
                key={tag.name}
                tag={tag}
                canWrite={canWrite}
                onDelete={() => setDeleting({ kind: "tag", name: tag.name })}
              />
            ))}
          </Box>
        </AsyncBoundary>
      )}

      {creatingRelease && (
        <CreateReleaseDialog
          owner={owner}
          name={name}
          tags={tags.tags}
          onClose={() => setCreatingRelease(false)}
          onCreated={(release) => {
            setCreatingRelease(false);
            afterChange(`Release ${release.name} was ${release.draft ? "saved as a draft" : "published"}.`);
          }}
        />
      )}

      {creatingTag && (
        <CreateTagDialog
          owner={owner}
          name={name}
          onClose={() => setCreatingTag(false)}
          onCreated={(tag) => {
            setCreatingTag(false);
            setView("tags");
            afterChange(`Tag ${tag.name} was created.`);
          }}
        />
      )}

      {deleting && (
        <ConfirmDeleteDialog
          target={deleting}
          onCancel={() => setDeleting(null)}
          onConfirm={confirmDelete}
        />
      )}
    </PageContainer>
  );
};

const ReleaseRow = ({ owner, name, release, canWrite, onDelete }) => (
  <Box
    sx={{
      border: "1px solid",
      borderColor: "border.default",
      borderRadius: 2,
      p: 3,
      bg: "canvas.default",
    }}
  >
    <Box sx={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between", gap: 3 }}>
      <Box sx={{ minWidth: 0 }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 2, flexWrap: "wrap" }}>
          <Link
            as={RouterLink}
            to={`/${owner}/${name}/releases/${release.id}`}
            sx={{ fontSize: 2, fontWeight: 600, color: "fg.default" }}
          >
            {release.name}
          </Link>
          {release.draft && <Label variant="secondary">Draft</Label>}
          {release.prerelease && <Label variant="attention">Pre-release</Label>}
        </Box>

        <Box sx={{ display: "flex", alignItems: "center", gap: 1, mt: 1 }}>
          <Octicon icon={TagIcon} size={14} sx={{ color: "fg.muted" }} />
          <Text sx={{ fontFamily: "mono", fontSize: 0, color: "fg.muted" }}>{release.tag}</Text>
          {release.authorName && (
            <Text sx={{ fontSize: 0, color: "fg.muted" }}>· by {release.authorName}</Text>
          )}
        </Box>
      </Box>

      {canWrite && (
        <Button
          variant="danger"
          size="small"
          leadingVisual={TrashIcon}
          onClick={onDelete}
          aria-label={`Delete release ${release.name}`}
        >
          Delete
        </Button>
      )}
    </Box>
  </Box>
);

const TagRow = ({ tag, canWrite, onDelete }) => (
  <Box
    sx={{
      display: "flex",
      alignItems: "center",
      justifyContent: "space-between",
      gap: 3,
      border: "1px solid",
      borderColor: "border.default",
      borderRadius: 2,
      px: 3,
      py: 2,
      bg: "canvas.default",
    }}
  >
    <Box sx={{ display: "flex", alignItems: "center", gap: 2, minWidth: 0 }}>
      <Octicon icon={TagIcon} size={16} sx={{ color: "fg.muted" }} />
      <Text sx={{ fontWeight: 600, color: "fg.default" }}>{tag.name}</Text>
      {tag.annotated && <Label variant="accent">Annotated</Label>}
      {tag.commit && (
        <Text sx={{ fontFamily: "mono", fontSize: 0, color: "fg.muted" }}>
          {tag.commit.slice(0, 7)}
        </Text>
      )}
    </Box>

    {canWrite && (
      <Button
        variant="danger"
        size="small"
        leadingVisual={TrashIcon}
        onClick={onDelete}
        aria-label={`Delete tag ${tag.name}`}
      >
        Delete
      </Button>
    )}
  </Box>
);

export default Releases;
