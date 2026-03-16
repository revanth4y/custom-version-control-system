import { useEffect, useMemo, useRef, useState } from "react";
import {
  ActionList,
  AnchoredOverlay,
  Box,
  Button,
  Spinner,
  Text,
  TextInput,
  Octicon,
} from "@primer/react";
import {
  GitBranchIcon,
  AlertIcon,
  PlusIcon,
  SearchIcon,
  TriangleDownIcon,
} from "@primer/octicons-react";

import BranchMeta from "./BranchMeta";
import CreateBranchDialog from "./CreateBranchDialog";
import CurrentBadge from "./CurrentBadge";
import { useBranches } from "../../hooks/useBranches";
import { useMutation } from "../../hooks/useMutation";
import { branchService } from "../../services/branchService";
import { currentFirst, filterBranches } from "../../utils/branches";

/** Below this many branches a search box is more clutter than help. */
const SEARCH_THRESHOLD = 6;

/**
 * Picks the branch being viewed, and can move HEAD to it.
 *
 * Two different things are deliberately kept apart. Choosing a branch to *look
 * at* only changes the URL - a read, available to anyone who can see the
 * repository. Making it the repository's current branch writes HEAD, and is
 * offered only to the owner as a separate, explicit action. Conflating them
 * would mean browsing someone's repository silently rewrote its state.
 *
 * Built on AnchoredOverlay rather than ActionMenu because the list needs a
 * search field: ActionMenu's typeahead treats every keystroke as a jump to a
 * matching item, so a text input inside it never receives what is typed.
 */
const BranchSelector = ({
  owner,
  name,
  currentRef,
  headBranch,
  canWrite,
  onRefChange,
  onHeadChanged,
}) => {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [creating, setCreating] = useState(false);
  const [switching, setSwitching] = useState(null);

  const searchInput = useRef(null);
  const { branches, loading, error, reload } = useBranches(owner, name, { enabled: open });
  const { run, error: switchError } = useMutation();

  const visible = useMemo(
    () => filterBranches(currentFirst(branches, headBranch), query),
    [branches, headBranch, query],
  );
  const searchable = branches.length > SEARCH_THRESHOLD;

  // The branch list is fetched only once the overlay opens, so the search field
  // does not exist yet when the focus trap initialises and initialFocusRef has
  // nothing to point at. Focusing it as it appears is what actually puts the
  // cursor where someone opening a long list expects to type.
  useEffect(() => {
    if (open && searchable) searchInput.current?.focus();
  }, [open, searchable]);

  const close = () => {
    setOpen(false);
    setQuery("");
  };

  const setAsHead = async (branch) => {
    setSwitching(branch);
    const result = await run(
      () => branchService.setHead(owner, name, branch),
      "The current branch could not be changed.",
    );
    setSwitching(null);
    if (result.ok) {
      onHeadChanged?.();
      close();
    }
  };

  return (
    <Box
      sx={{ display: "inline-flex", flexDirection: "column", gap: 1, minWidth: 0, maxWidth: "100%" }}
    >
      <AnchoredOverlay
        open={open}
        onOpen={() => setOpen(true)}
        onClose={close}
        width="large"
        // The preset widths are fixed pixel values, so on a narrow screen a
        // "large" overlay is wider than the viewport and gets positioned partly
        // off the left edge. Capping it against the viewport keeps the whole
        // panel reachable on a phone.
        overlayProps={{ sx: { maxWidth: "calc(100vw - 16px)" } }}
        renderAnchor={(anchorProps) => (
          <Button
            {...anchorProps}
            leadingVisual={GitBranchIcon}
            trailingVisual={TriangleDownIcon}
            aria-label={`Branch: ${currentRef}`}
            sx={{ maxWidth: "280px" }}
          >
            <Text
              sx={{
                overflow: "hidden",
                textOverflow: "ellipsis",
                whiteSpace: "nowrap",
                display: "block",
                fontFamily: "mono",
                fontSize: 0,
              }}
              title={currentRef}
            >
              {currentRef}
            </Text>
          </Button>
        )}
      >
        <Box sx={{ display: "flex", flexDirection: "column", maxHeight: "min(60vh, 460px)" }}>
          <Box
            sx={{
              px: 3,
              pt: 3,
              pb: searchable ? 2 : 3,
              borderBottom: "1px solid",
              borderColor: "border.muted",
            }}
          >
            <Text
              sx={{
                fontSize: 0,
                fontWeight: 600,
                color: "fg.muted",
                display: "block",
                mb: searchable ? 2 : 0,
              }}
            >
              Switch branches
            </Text>
            {searchable && (
              <TextInput
                ref={searchInput}
                block
                size="small"
                leadingVisual={SearchIcon}
                value={query}
                placeholder="Find a branch"
                aria-label="Find a branch"
                onChange={(event) => setQuery(event.target.value)}
              />
            )}
          </Box>

          <Box sx={{ overflowY: "auto", flex: 1 }}>
            {loading && (
              <Box sx={{ display: "flex", alignItems: "center", gap: 2, p: 3 }}>
                <Spinner size="small" />
                <Text sx={{ fontSize: 1, color: "fg.muted" }}>Loading branches</Text>
              </Box>
            )}

            {error && (
              <Box sx={{ display: "flex", alignItems: "flex-start", gap: 2, p: 3 }}>
                <Octicon icon={AlertIcon} sx={{ color: "danger.fg", mt: "2px" }} />
                <Box>
                  <Text sx={{ fontSize: 1, display: "block" }}>Could not load branches</Text>
                  <Button size="small" onClick={reload} sx={{ mt: 2 }}>
                    Try again
                  </Button>
                </Box>
              </Box>
            )}

            {!loading && !error && branches.length === 0 && (
              <Box sx={{ p: 3 }}>
                <Text sx={{ fontSize: 1, color: "fg.muted" }}>
                  No branches yet. The first commit creates one.
                </Text>
              </Box>
            )}

            {!loading && !error && branches.length > 0 && visible.length === 0 && (
              <Box sx={{ p: 3 }}>
                <Text sx={{ fontSize: 1, color: "fg.muted", display: "block" }}>
                  No branch matches that search.
                </Text>
                <Button size="small" onClick={() => setQuery("")} sx={{ mt: 2 }}>
                  Clear search
                </Button>
              </Box>
            )}

            {visible.length > 0 && (
              <ActionList selectionVariant="single">
                {visible.map((branch) => (
                  <ActionList.Item
                    key={branch.name}
                    selected={branch.name === currentRef}
                    onSelect={() => {
                      onRefChange(branch.name);
                      close();
                    }}
                  >
                    {/* The only tick in the row is Primer's own, marking the
                        branch being viewed. HEAD is a worded badge instead, so
                        the two states never compete as identical glyphs. */}
                    <ActionList.LeadingVisual>
                      <GitBranchIcon />
                    </ActionList.LeadingVisual>

                    <Box sx={{ display: "flex", alignItems: "center", gap: 2, minWidth: 0 }}>
                      <Text sx={{ fontFamily: "mono", overflowWrap: "anywhere", minWidth: 0 }}>
                        {branch.name}
                      </Text>
                      {branch.name === headBranch && <CurrentBadge />}
                    </Box>

                    <ActionList.Description variant="block">
                      <BranchMeta tip={branch.tip} />
                    </ActionList.Description>

                    {canWrite && branch.name !== headBranch && (
                      <ActionList.TrailingVisual>
                        <Box
                          as="span"
                          role="button"
                          tabIndex={0}
                          aria-label={`Make ${branch.name} the current branch`}
                          onClick={(event) => {
                            event.stopPropagation();
                            setAsHead(branch.name);
                          }}
                          onKeyDown={(event) => {
                            if (event.key === "Enter" || event.key === " ") {
                              event.stopPropagation();
                              event.preventDefault();
                              setAsHead(branch.name);
                            }
                          }}
                          sx={{
                            fontSize: 0,
                            color: "accent.fg",
                            cursor: "pointer",
                            whiteSpace: "nowrap",
                            "&:hover": { textDecoration: "underline" },
                          }}
                        >
                          {switching === branch.name ? "setting..." : "set current"}
                        </Box>
                      </ActionList.TrailingVisual>
                    )}
                  </ActionList.Item>
                ))}
              </ActionList>
            )}
          </Box>

          {canWrite && (
            <Box sx={{ borderTop: "1px solid", borderColor: "border.muted", p: 2 }}>
              <Button
                leadingVisual={PlusIcon}
                variant="invisible"
                block
                onClick={() => {
                  setOpen(false);
                  setCreating(true);
                }}
                sx={{ justifyContent: "flex-start" }}
              >
                New branch
              </Button>
            </Box>
          )}
        </Box>
      </AnchoredOverlay>

      {switchError && <Text sx={{ fontSize: 0, color: "danger.fg" }}>{switchError}</Text>}

      {creating && (
        <CreateBranchDialog
          owner={owner}
          name={name}
          branches={branches}
          defaultStartPoint={currentRef}
          onClose={() => setCreating(false)}
          onCreated={(created) => {
            setCreating(false);
            setQuery("");
            onRefChange(created.name);
          }}
        />
      )}
    </Box>
  );
};

export default BranchSelector;
