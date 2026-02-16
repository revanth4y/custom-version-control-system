import { useRef } from "react";
import { Box, Button, Octicon, Text } from "@primer/react";
import { GitBranchIcon } from "@primer/octicons-react";

import ModalDialog from "../common/ModalDialog";
import Notice from "../common/Notice";
import BranchMeta from "./BranchMeta";
import { useMutation } from "../../hooks/useMutation";
import { branchService } from "../../services/branchService";

/**
 * Confirming the deletion of a branch.
 *
 * Worth spelling out what is actually destroyed, because "delete" reads as
 * worse than it is: a branch is a name pointing at a commit, and removing the
 * name leaves every object where it was. Saying so turns an alarming action
 * into an understood one - and it is true, because the object store is
 * append-only and nothing in this system collects garbage.
 *
 * Focus starts on Cancel rather than the destructive button, so a stray Enter
 * dismisses the dialog instead of confirming it.
 */
const DeleteBranchDialog = ({ owner, name, branch, onClose, onDeleted }) => {
  const cancelButton = useRef(null);
  const { run, pending, error } = useMutation();

  const confirm = async () => {
    const result = await run(
      () => branchService.remove(owner, name, branch.name),
      "The branch could not be deleted.",
    );
    if (result.ok) onDeleted(branch.name);
  };

  return (
    <ModalDialog
      title="Delete this branch?"
      role="alertdialog"
      onClose={onClose}
      initialFocusRef={cancelButton}
      actions={
        <>
          <Button ref={cancelButton} onClick={onClose} disabled={pending}>
            Cancel
          </Button>
          <Button variant="danger" onClick={confirm} disabled={pending}>
            {pending ? "Deleting..." : "Delete branch"}
          </Button>
        </>
      }
    >
      <Box sx={{ display: "flex", flexDirection: "column", gap: 3 }}>
        {error && <Notice variant="danger">{error}</Notice>}

        <Box
          sx={{
            bg: "canvas.inset",
            border: "1px solid",
            borderColor: "border.default",
            borderRadius: 2,
            p: 3,
            minWidth: 0,
          }}
        >
          <Box sx={{ display: "flex", alignItems: "center", gap: 2, mb: 1, minWidth: 0 }}>
            <Octicon icon={GitBranchIcon} sx={{ color: "fg.muted", flexShrink: 0 }} />
            <Text
              sx={{ fontFamily: "mono", fontWeight: 600, overflowWrap: "anywhere", minWidth: 0 }}
            >
              {branch.name}
            </Text>
          </Box>
          <BranchMeta tip={branch.tip} showAuthor />
        </Box>

        <Notice variant="info">
          Deleting a branch removes the reference; commits and objects are not deleted.
        </Notice>
      </Box>
    </ModalDialog>
  );
};

export default DeleteBranchDialog;
