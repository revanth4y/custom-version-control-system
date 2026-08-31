import { useCallback, useMemo } from "react";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import { Box, Heading, Label, Link, Text } from "@primer/react";
import { FileDirectoryIcon, FileIcon, GitCommitIcon } from "@primer/octicons-react";

import PageContainer from "../components/layout/PageContainer";
import RouterLink from "../components/common/RouterLink";
import { AsyncBoundary, EmptyState } from "../components/common/states";
import BranchSelector from "../components/branch/BranchSelector";
import { useAsync } from "../hooks/useAsync";
import { useRepository } from "../hooks/useRepository";
import { commitService } from "../services/commitService";
import { contentService } from "../services/contentService";
import {
  abbreviate,
  ancestry,
  describeEntry,
  entryAt,
  kindOf,
  merkleUrl,
  normalise,
  parentPath,
} from "../utils/merkleTree";

/**
 * A 404 from a content endpoint is an answer, not a failure.
 *
 * Asking for a path this commit's tree does not contain succeeds as a request
 * and reports that nothing is there. Letting it reach the error state would tell
 * a reader who mistyped a path that the application broke, and would hide the
 * one thing they need to know. A deep path makes this unavoidable rather than
 * incidental: `a/b/c` where `a/b` does not exist means the parent listing itself
 * 404s, so absence has to be handled here rather than inferred from an empty
 * result.
 *
 * Anything else - a network failure, a 500 - still throws and still surfaces.
 */
const absentOn404 = async (task) => {
  try {
    return await task();
  } catch (error) {
    if (error?.response?.status === 404) {
      return null;
    }
    throw error;
  }
};

/**
 * The repository as the Merkle tree it actually is.
 *
 * A commit names one tree; a tree names blobs and other trees by their ids; and
 * an id is a hash of the child's own contents, so a tree's hash covers
 * everything beneath it. This view walks that structure and shows the real ids
 * at each step, which is the one thing the rest of the interface never does.
 *
 * <strong>The walk is pinned to a commit, not to a ref.</strong> The revision is
 * resolved once to a commit id and every subsequent request uses that id, so
 * descending cannot drift onto a different snapshot if the branch moves while
 * someone is reading.
 *
 * <strong>A tree does not report its own id, so it is read from its parent.</strong>
 * The listing endpoint answers with entries, not with the hash of the directory
 * being listed. The root's id comes from the commit; every other object's comes
 * from the entry its parent recorded for it - which is not a workaround but the
 * relationship this page exists to show.
 *
 * Nothing here verifies a hash. The bytes an id is taken over are not exposed by
 * any endpoint, so the browser could not check one even if it wanted to, and the
 * interface does not suggest otherwise.
 */
const MerkleExplorer = () => {
  const { owner, name, head, canWrite, reloadHead } = useRepository();
  const params = useParams();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();

  const refName = params.ref ? decodeURIComponent(params.ref) : head?.branch ?? "HEAD";
  const path = normalise(searchParams.get("path"));

  // One commit, resolved from the revision. Everything below is addressed by
  // this id rather than by the ref, so the whole walk is one snapshot.
  const commit = useAsync(
    () => commitService.historyPage(owner, name, { ref: refName, limit: 1 }),
    [owner, name, refName],
  );
  const head0 = commit.data?.commits?.[0] ?? null;
  const commitSha = head0?.sha ?? null;

  /* The parent listing, which is where this object's own id is recorded. Not
     fetched at the root, where the commit supplies the tree id instead. */
  const parent = useAsync(
    () =>
      commitSha && path
        ? absentOn404(() =>
            contentService.tree(owner, name, { ref: commitSha, path: parentPath(path) }),
          )
        : Promise.resolve(null),
    [owner, name, commitSha, path],
  );

  const selfEntry = useMemo(
    () => (path ? entryAt(parent.data?.entries, path) : null),
    [parent.data, path],
  );

  const isTree = path === "" || selfEntry?.type === "dir";

  // A directory needs its own listing; a blob needs its object detail. Asking
  // for the wrong one is a 404, so the parent's entry decides which to ask for.
  const listing = useAsync(
    () =>
      commitSha && isTree && (path === "" || selfEntry)
        ? absentOn404(() => contentService.tree(owner, name, { ref: commitSha, path }))
        : Promise.resolve(null),
    [owner, name, commitSha, path, isTree, selfEntry],
  );

  const blob = useAsync(
    () =>
      commitSha && !isTree && selfEntry
        ? absentOn404(() => contentService.blob(owner, name, { ref: commitSha, path }))
        : Promise.resolve(null),
    [owner, name, commitSha, path, isTree, selfEntry],
  );

  const selected = useMemo(() => {
    if (!commitSha) {
      return null;
    }
    if (path === "") {
      return { kind: "tree", name: "root tree", id: head0?.tree, mode: null, size: null };
    }
    if (!selfEntry) {
      return null;
    }
    if (selfEntry.type === "dir") {
      return { kind: "tree", name: selfEntry.name, id: selfEntry.id, mode: selfEntry.mode, size: null };
    }
    return {
      kind: "blob",
      name: selfEntry.name,
      id: selfEntry.id,
      mode: selfEntry.mode,
      size: blob.data?.size ?? null,
      binary: blob.data?.binary ?? null,
    };
  }, [commitSha, path, head0, selfEntry, blob.data]);

  const changeRef = useCallback(
    // The path is dropped: it may not exist on the other revision, and guessing
    // that it does would land the reader on a not-found state they did not ask for.
    (branch) => navigate(merkleUrl(owner, name, branch, "")),
    [navigate, owner, name],
  );

  const hasNoCommits = !head?.commit;
  const loading = commit.loading || parent.loading || listing.loading || blob.loading;
  const error = commit.error ?? parent.error ?? listing.error ?? blob.error;

  // A path that resolves to nothing is a missing object, not a failure: the
  // request succeeded and the answer is that no such entry exists.
  const notFound = Boolean(commitSha && path && !parent.loading && !parent.error && !selfEntry);

  const reload = useCallback(() => {
    commit.reload();
    parent.reload();
    listing.reload();
    blob.reload();
  }, [commit, parent, listing, blob]);

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
          <Heading as="h2" sx={{ fontSize: 2, fontWeight: 600 }}>
            Objects
          </Heading>
          <Text sx={{ fontSize: 0, color: "fg.muted" }}>
            Every id is a hash of the object&apos;s own contents, so a tree&apos;s id covers
            everything beneath it.
          </Text>
        </Box>
      </Box>

      {hasNoCommits ? (
        <Panel>
          <EmptyState
            icon={GitCommitIcon}
            title="No commits yet"
            message="Nothing has been committed to this repository, so there are no objects to explore."
            minHeight="220px"
          />
        </Panel>
      ) : (
        <AsyncBoundary
          loading={loading && !head0}
          error={error}
          onRetry={reload}
          loadingLabel="Loading objects"
          minHeight="260px"
        >
          {head0 && (
            <>
              <CommitCard commit={head0} owner={owner} name={name} />

              <Box sx={{ mt: 3, mb: 2 }}>
                <Trail owner={owner} name={name} refName={refName} path={path} />
              </Box>

              {notFound ? (
                <Panel>
                  <EmptyState
                    icon={FileIcon}
                    title="No such object"
                    message={`Nothing named ${path} exists in this commit's tree. It may have been added later or removed earlier.`}
                    minHeight="200px"
                  />
                </Panel>
              ) : (
                <>
                  {selected && <ObjectCard object={selected} />}

                  {isTree && listing.data && (
                    <Box sx={{ mt: 3 }}>
                      <Panel>
                        <Box sx={{ overflowX: "auto" }}>
                          <Box
                            as="ul"
                            aria-label={`Entries of ${path || "the root tree"}`}
                            sx={{ listStyle: "none", m: 0, p: 0, minWidth: "320px" }}
                          >
                            {listing.data.entries.length === 0 ? (
                              <Box as="li" sx={{ p: 3 }}>
                                <Text sx={{ fontSize: 1, color: "fg.muted" }}>
                                  This tree has no entries.
                                </Text>
                              </Box>
                            ) : (
                              listing.data.entries.map((entry) => (
                                <EntryRow
                                  key={entry.path}
                                  entry={entry}
                                  onOpen={() =>
                                    navigate(merkleUrl(owner, name, refName, entry.path))
                                  }
                                />
                              ))
                            )}
                          </Box>
                        </Box>
                      </Panel>
                    </Box>
                  )}
                </>
              )}
            </>
          )}
        </AsyncBoundary>
      )}
    </PageContainer>
  );
};

/**
 * The commit the walk is pinned to.
 *
 * Shows the commit's own id, the tree it names, and the commits it descends
 * from - the top of the chain every object below hangs off.
 */
const CommitCard = ({ commit, owner, name }) => (
  <Panel>
    <Box sx={{ p: 3, display: "flex", flexDirection: "column", gap: 2 }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 2, flexWrap: "wrap" }}>
        <Label variant="accent">commit</Label>
        <Link
          as={RouterLink}
          to={`/${owner}/${name}/commit/${commit.sha}`}
          sx={{ fontFamily: "mono", fontSize: 1 }}
          title={commit.sha}
        >
          {commit.shortSha}
        </Link>
        <Text sx={{ fontSize: 1, color: "fg.muted", minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
          {(commit.message ?? "").split("\n")[0]}
        </Text>
      </Box>

      <Field label="tree" value={commit.tree} />
      <Field
        label={commit.parents.length === 1 ? "parent" : "parents"}
        value={commit.parents.length === 0 ? "none — this is a root commit" : commit.parents.join("  ")}
        mono={commit.parents.length > 0}
      />
    </Box>
  </Panel>
);

/** The object currently selected, with the values the server reported for it. */
const ObjectCard = ({ object }) => (
  <Panel>
    <Box sx={{ p: 3, display: "flex", flexDirection: "column", gap: 2 }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 2, flexWrap: "wrap" }}>
        <Label variant={object.kind === "tree" ? "success" : "secondary"}>{object.kind}</Label>
        <Text sx={{ fontSize: 1, fontWeight: 600 }}>{object.name}</Text>
      </Box>

      <Field label="id" value={object.id} />
      {object.mode && <Field label="mode" value={object.mode} />}
      {typeof object.size === "number" && (
        <Field label="size" value={`${object.size} bytes`} mono={false} />
      )}
      {object.binary === true && <Field label="content" value="binary" mono={false} />}
    </Box>
  </Panel>
);

/**
 * One labelled value.
 *
 * Ids are shown in full rather than abbreviated: this is the one view where the
 * exact value is the point, and a truncated hash cannot be compared against
 * anything.
 */
const Field = ({ label, value, mono = true }) => (
  <Box sx={{ display: "flex", gap: 2, alignItems: "baseline", flexWrap: "wrap" }}>
    <Text sx={{ fontSize: 0, color: "fg.muted", minWidth: "56px" }}>{label}</Text>
    <Text
      sx={{
        fontFamily: mono ? "mono" : "normal",
        fontSize: 0,
        wordBreak: "break-all",
        minWidth: 0,
      }}
    >
      {value}
    </Text>
  </Box>
);

/** The chain from the commit's tree down to the current object. */
const Trail = ({ owner, name, refName, path }) => {
  const steps = ancestry(path);

  return (
    <Box
      as="nav"
      aria-label="Object ancestry"
      sx={{ display: "flex", flexWrap: "wrap", alignItems: "center", gap: 1, fontSize: 1, minWidth: 0 }}
    >
      {steps.length === 0 ? (
        <Text sx={{ fontWeight: 600 }}>root tree</Text>
      ) : (
        <Link as={RouterLink} to={merkleUrl(owner, name, refName, "")}>
          root tree
        </Link>
      )}

      {steps.map((step, index) => (
        <Box key={step.path} sx={{ display: "inline-flex", alignItems: "center", gap: 1 }}>
          <Text aria-hidden="true" sx={{ color: "fg.muted" }}>
            /
          </Text>
          {index === steps.length - 1 ? (
            <Text sx={{ fontWeight: 600 }}>{step.name}</Text>
          ) : (
            <Link as={RouterLink} to={merkleUrl(owner, name, refName, step.path)}>
              {step.name}
            </Link>
          )}
        </Box>
      ))}
    </Box>
  );
};

/**
 * One child of the current tree.
 *
 * A button, because activating it moves the walk. The accessible name carries
 * what the row shows in separate columns - kind, name, and the id the parent
 * tree recorded - which read aloud would otherwise be three unrelated fragments.
 */
const EntryRow = ({ entry, onOpen }) => (
  <Box as="li">
    <Box
      as="button"
      type="button"
      onClick={onOpen}
      aria-label={describeEntry(entry)}
      sx={{
        display: "flex",
        alignItems: "center",
        gap: 2,
        width: "100%",
        px: 3,
        py: 2,
        border: 0,
        bg: "transparent",
        color: "fg.default",
        textAlign: "left",
        cursor: "pointer",
        font: "inherit",
        borderTop: "1px solid",
        borderColor: "border.muted",
        "&:first-of-type": { borderTop: 0 },
        "&:hover": { bg: "canvas.default" },
        "&:focus-visible": {
          outline: "2px solid",
          outlineColor: "accent.fg",
          outlineOffset: "-2px",
        },
      }}
    >
      <Box aria-hidden="true" sx={{ display: "inline-flex", color: "fg.muted", flexShrink: 0 }}>
        {entry.type === "dir" ? <FileDirectoryIcon size={16} /> : <FileIcon size={16} />}
      </Box>

      <Text
        sx={{
          fontSize: 1,
          minWidth: 0,
          overflow: "hidden",
          textOverflow: "ellipsis",
          whiteSpace: "nowrap",
        }}
      >
        {entry.name}
      </Text>

      <Text aria-hidden="true" sx={{ fontSize: 0, color: "fg.muted", flexShrink: 0, ml: 2 }}>
        {kindOf(entry)}
      </Text>

      <Text
        aria-hidden="true"
        sx={{ ml: "auto", fontFamily: "mono", fontSize: 0, color: "fg.muted", flexShrink: 0 }}
      >
        {abbreviate(entry.id)}
      </Text>
    </Box>
  </Box>
);

const Panel = ({ children }) => (
  <Box
    sx={{
      bg: "canvas.subtle",
      border: "1px solid",
      borderColor: "border.default",
      borderRadius: 2,
      overflow: "hidden",
    }}
  >
    {children}
  </Box>
);

export default MerkleExplorer;
