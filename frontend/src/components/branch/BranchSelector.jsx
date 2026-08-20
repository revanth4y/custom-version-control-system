import { useState } from "react";
import { ActionMenu, ActionList, Button, Box, Text, Spinner, Octicon } from "@primer/react";
import { GitBranchIcon, AlertIcon } from "@primer/octicons-react";

import { useAsync } from "../../hooks/useAsync";
import { branchService } from "../../services/branchService";
import { errorMessage } from "../../services/api";

/**
 * Picks the branch being viewed, and can move HEAD to it.
 *
 * Two different things are deliberately kept apart. Choosing a branch to *look
 * at* only changes the URL — a read, and available to anyone who can see the
 * repository. Making it the repository's current branch writes to HEAD, and is
 * offered only to the owner, as a separate explicit action. Conflating them
 * would mean browsing someone's repository silently rewrote its state.
 */
const BranchSelector = ({ owner, name, currentRef, headBranch, canWrite, onRefChange, onHeadChanged }) => {
  const [open, setOpen] = useState(false);
  const [switching, setSwitching] = useState(null);
  const [switchError, setSwitchError] = useState(null);

  // Only fetched once the menu is opened: most visits never open it.
  const branches = useAsync(
    () => (open ? branchService.list(owner, name) : Promise.resolve(null)),
    [open, owner, name],
  );

  const setAsHead = async (branch) => {
    setSwitching(branch);
    setSwitchError(null);
    try {
      await branchService.setHead(owner, name, branch);
      onHeadChanged?.();
      setOpen(false);
    } catch (caught) {
      setSwitchError(errorMessage(caught, "Could not switch the current branch."));
    } finally {
      setSwitching(null);
    }
  };

  return (
    <Box sx={{ display: "inline-flex", flexDirection: "column", gap: 1, minWidth: 0 }}>
      <ActionMenu open={open} onOpenChange={setOpen}>
        <ActionMenu.Button leadingVisual={GitBranchIcon} sx={{ maxWidth: "260px" }}>
          <Text
            sx={{
              overflow: "hidden",
              textOverflow: "ellipsis",
              whiteSpace: "nowrap",
              display: "block",
            }}
            title={currentRef}
          >
            {currentRef}
          </Text>
        </ActionMenu.Button>

        <ActionMenu.Overlay width="medium">
          {branches.loading && (
            <Box sx={{ display: "flex", alignItems: "center", gap: 2, p: 3 }}>
              <Spinner size="small" />
              <Text sx={{ fontSize: 1, color: "fg.muted" }}>Loading branches</Text>
            </Box>
          )}

          {branches.error && (
            <Box sx={{ display: "flex", alignItems: "flex-start", gap: 2, p: 3 }}>
              <Octicon icon={AlertIcon} sx={{ color: "danger.fg", mt: "2px" }} />
              <Box>
                <Text sx={{ fontSize: 1, display: "block" }}>Could not load branches</Text>
                <Button size="small" onClick={branches.reload} sx={{ mt: 2 }}>
                  Try again
                </Button>
              </Box>
            </Box>
          )}

          {!branches.loading && !branches.error && (branches.data?.length ?? 0) === 0 && (
            <Box sx={{ p: 3 }}>
              <Text sx={{ fontSize: 1, color: "fg.muted" }}>
                No branches yet. The first commit creates one.
              </Text>
            </Box>
          )}

          {(branches.data?.length ?? 0) > 0 && (
            <ActionList selectionVariant="single">
              <ActionList.Group>
                <ActionList.GroupHeading>Branches</ActionList.GroupHeading>
                {branches.data.map((branch) => (
                  <ActionList.Item
                    key={branch.name}
                    selected={branch.name === currentRef}
                    onSelect={() => {
                      onRefChange(branch.name);
                      setOpen(false);
                    }}
                  >
                    {/* Primer's single-selection tick already marks the branch
                        being viewed. HEAD is called out in the description
                        instead, so the two never land on the same glyph. */}
                    <ActionList.LeadingVisual>
                      <GitBranchIcon />
                    </ActionList.LeadingVisual>

                    <Text sx={{ wordBreak: "break-word" }}>{branch.name}</Text>

                    <ActionList.Description variant="block">
                      <Text sx={{ fontFamily: "mono", fontSize: 0 }}>
                        {branch.commit?.slice(0, 7)}
                      </Text>
                      {branch.name === headBranch && (
                        <Text sx={{ ml: 2, color: "success.fg" }}>current</Text>
                      )}
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
                          {switching === branch.name ? "setting…" : "set current"}
                        </Box>
                      </ActionList.TrailingVisual>
                    )}
                  </ActionList.Item>
                ))}
              </ActionList.Group>
            </ActionList>
          )}
        </ActionMenu.Overlay>
      </ActionMenu>

      {switchError && (
        <Text sx={{ fontSize: 0, color: "danger.fg" }}>{switchError}</Text>
      )}
    </Box>
  );
};

export default BranchSelector;
