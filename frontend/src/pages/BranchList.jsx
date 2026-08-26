import { useMemo, useState } from "react";
import { Box, Button, Heading, Text, TextInput, Select, Link } from "@primer/react";
import Octicon from "../components/common/Octicon";
import { GitBranchIcon, GitMergeIcon, PlusIcon, SearchIcon, TrashIcon } from "@primer/octicons-react";
import RouterLink from "../components/common/RouterLink";

import PageContainer from "../components/layout/PageContainer";
import { AsyncBoundary, EmptyState } from "../components/common/states";
import Notice from "../components/common/Notice";
import BranchMeta from "../components/branch/BranchMeta";
import CurrentBadge from "../components/branch/CurrentBadge";
import CreateBranchDialog from "../components/branch/CreateBranchDialog";
import DeleteBranchDialog from "../components/branch/DeleteBranchDialog";
import { useBranches } from "../hooks/useBranches";
import { useMutation } from "../hooks/useMutation";
import { useRepository } from "../hooks/useRepository";
import { branchService } from "../services/branchService";
import { SORT_MODES, filterBranches, sortBranches } from "../utils/branches";

/**
 * Every branch in the repository, with what each one points at.
 *
 * Unlike the selector, this page respects the chosen sort exactly and does not
 * pin the current branch to the top: it offers an explicit ordering, and
 * quietly overriding it would make the control a lie.
 */
const BranchList = () => {
  const { owner, name, head, canWrite, reloadHead } = useRepository();

  const [query, setQuery] = useState("");
  const [sort, setSort] = useState("activity");
  const [creating, setCreating] = useState(false);
  const [deleting, setDeleting] = useState(null);
  const [notice, setNotice] = useState(null);

  const { branches, loading, error, reload } = useBranches(owner, name);
  const { run, error: switchError } = useMutation();
  const [switching, setSwitching] = useState(null);

  const visible = useMemo(
    () => sortBranches(filterBranches(branches, query), sort),
    [branches, query, sort],
  );

  const setAsHead = async (branch) => {
    setSwitching(branch);
    const result = await run(
      () => branchService.setHead(owner, name, branch),
      "The current branch could not be changed.",
    );
    setSwitching(null);
    if (result.ok) {
      // HEAD alone changed; the repository's metadata did not, so only the
      // reference is refetched.
      reloadHead();
      reload();
      setNotice({ variant: "success", text: `The repository is now on ${branch}.` });
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
        <Box sx={{ minWidth: 0 }}>
          <Heading as="h2" sx={{ fontSize: 3, fontWeight: 600, mb: 1 }}>
            Branches
          </Heading>
          <Text sx={{ fontSize: 1, color: "fg.muted" }}>
            A branch is a name that points at a commit and moves as you commit to it.
          </Text>
        </Box>

        <Box sx={{ display: "flex", gap: 2, flexWrap: "wrap" }}>
          <Button as={RouterLink} to={`/${owner}/${name}/merge`} leadingVisual={GitMergeIcon}>
            Merge
          </Button>
          {canWrite && (
            <Button leadingVisual={PlusIcon} variant="primary" onClick={() => setCreating(true)}>
              New branch
            </Button>
          )}
        </Box>
      </Box>

      {notice && (
        <Box sx={{ mb: 3 }}>
          <Notice variant={notice.variant}>{notice.text}</Notice>
        </Box>
      )}
      {switchError && (
        <Box sx={{ mb: 3 }}>
          <Notice variant="danger">{switchError}</Notice>
        </Box>
      )}

      <Box sx={{ display: "flex", gap: 2, flexWrap: "wrap", mb: 3 }}>
        <Box sx={{ flex: "1 1 220px", minWidth: 0 }}>
          <TextInput
            block
            leadingVisual={SearchIcon}
            value={query}
            placeholder="Find a branch"
            aria-label="Find a branch"
            onChange={(event) => setQuery(event.target.value)}
          />
        </Box>
        <Select
          value={sort}
          aria-label="Sort branches"
          onChange={(event) => setSort(event.target.value)}
          sx={{ flexShrink: 0 }}
        >
          {SORT_MODES.map((mode) => (
            <Select.Option key={mode.key} value={mode.key}>
              {mode.label}
            </Select.Option>
          ))}
        </Select>
      </Box>

      <AsyncBoundary
        loading={loading}
        error={error}
        onRetry={reload}
        loadingLabel="Loading branches"
        minHeight="220px"
      >
        {branches.length === 0 ? (
          <Panel>
            <EmptyState
              icon={GitBranchIcon}
              title="No branches yet"
              message={
                canWrite
                  ? "The first commit creates a branch. Until then there is no reference to list."
                  : "Nothing has been committed to this repository yet."
              }
              minHeight="220px"
            />
          </Panel>
        ) : visible.length === 0 ? (
          <Panel>
            <EmptyState
              icon={SearchIcon}
              title="No branch matches your search"
              message="Try a shorter search, or clear it to see every branch."
              action={<Button onClick={() => setQuery("")}>Clear search</Button>}
              minHeight="220px"
            />
          </Panel>
        ) : (
          <Panel>
            {visible.map((branch, index) => (
              <BranchRow
                key={branch.name}
                owner={owner}
                name={name}
                branch={branch}
                isHead={branch.name === head?.branch}
                canWrite={canWrite}
                first={index === 0}
                switching={switching === branch.name}
                onSetHead={() => setAsHead(branch.name)}
                onDelete={() => setDeleting(branch)}
              />
            ))}
          </Panel>
        )}
      </AsyncBoundary>

      {creating && (
        <CreateBranchDialog
          owner={owner}
          name={name}
          branches={branches}
          defaultStartPoint={head?.branch ?? "HEAD"}
          onClose={() => setCreating(false)}
          onCreated={(created) => {
            setCreating(false);
            reload();
            setNotice({ variant: "success", text: `Created ${created.name}.` });
          }}
        />
      )}

      {deleting && (
        <DeleteBranchDialog
          owner={owner}
          name={name}
          branch={deleting}
          onClose={() => setDeleting(null)}
          onDeleted={(deleted) => {
            setDeleting(null);
            reload();
            setNotice({ variant: "success", text: `Deleted ${deleted}. Its commits remain.` });
          }}
        />
      )}
    </PageContainer>
  );
};

/**
 * One branch.
 *
 * The row is a column on a narrow screen and a row on a wide one, so a long
 * nested name never has to share a line with the actions that follow it.
 */
const BranchRow = ({
  owner,
  name,
  branch,
  isHead,
  canWrite,
  first,
  switching,
  onSetHead,
  onDelete,
}) => (
  <Box
    sx={{
      display: "flex",
      flexDirection: ["column", "row"],
      alignItems: ["stretch", "center"],
      justifyContent: "space-between",
      gap: 3,
      px: 3,
      py: 3,
      borderTop: first ? "none" : "1px solid",
      borderColor: "border.muted",
      "&:hover": { bg: "canvas.inset" },
    }}
  >
    <Box sx={{ minWidth: 0, flex: 1 }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 2, mb: 1, minWidth: 0 }}>
        <Octicon icon={GitBranchIcon} sx={{ color: "fg.muted", flexShrink: 0 }} />
        <Link
          as={RouterLink}
          to={`/${owner}/${name}/tree/${encodeURIComponent(branch.name)}`}
          sx={{
            fontFamily: "mono",
            fontWeight: 600,
            color: "accent.fg",
            // A nested name is long and unbroken; it wraps rather than pushing
            // the row wider than the viewport.
            overflowWrap: "anywhere",
            minWidth: 0,
          }}
        >
          {branch.name}
        </Link>
        {isHead && <CurrentBadge />}
      </Box>

      <BranchMeta tip={branch.tip} showAuthor />
    </Box>

    {/* No "browse" button: the branch name above is already a link to it, and a
        second control for the same destination made the action group a
        different width on the current branch's row than on every other. */}
    <Box sx={{ display: "flex", alignItems: "center", gap: 2, flexShrink: 0, flexWrap: "wrap" }}>
      {canWrite && !isHead && (
        <Button size="small" onClick={onSetHead} disabled={switching}>
          {switching ? "Setting..." : "Set current"}
        </Button>
      )}

      {canWrite &&
        (isHead ? (
          // The engine refuses to delete the branch HEAD names. Showing the
          // control disabled, with the reason, explains the rule; hiding it
          // would leave someone hunting for a button that is not there.
          <Button
            size="small"
            disabled
            leadingVisual={TrashIcon}
            aria-label={`Cannot delete ${branch.name}: it is the current branch`}
            title="The current branch cannot be deleted. Switch to another branch first."
            // Not the danger variant: Primer renders a disabled danger button
            // filled and dark, which reads as more emphasised than the enabled
            // ones beside it. A plain disabled button reads as unavailable,
            // which is what it is.
            sx={{ color: "fg.subtle" }}
          >
            Delete
          </Button>
        ) : (
          <Button
            size="small"
            variant="danger"
            leadingVisual={TrashIcon}
            aria-label={`Delete ${branch.name}`}
            onClick={onDelete}
          >
            Delete
          </Button>
        ))}
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

export default BranchList;
