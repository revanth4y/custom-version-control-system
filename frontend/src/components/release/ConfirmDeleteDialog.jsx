import { Box, Button } from "@primer/react";

import ModalDialog from "../common/ModalDialog";
import Notice from "../common/Notice";

/**
 * Confirming a deletion, saying plainly what it will and will not remove.
 *
 * The distinction matters enough to spell out on screen: deleting a release
 * leaves its tag, and deleting a tag leaves the commits it named. Neither
 * destroys history.
 */
const ConfirmDeleteDialog = ({ target, onCancel, onConfirm }) => {
  const isRelease = target.kind === "release";

  return (
    <ModalDialog
      title={isRelease ? "Delete release" : "Delete tag"}
      onClose={onCancel}
      actions={
        <>
          <Button onClick={onCancel}>Cancel</Button>
          <Button variant="danger" onClick={onConfirm}>
            {isRelease ? "Delete release" : "Delete tag"}
          </Button>
        </>
      }
    >
      <Box sx={{ display: "flex", flexDirection: "column", gap: 3 }}>
        <Notice variant="warning">
          {isRelease ? (
            <>
              <strong>{target.name}</strong> will be removed. The tag it names stays exactly
              where it is, and no history is deleted.
            </>
          ) : (
            <>
              <strong>{target.name}</strong> will be removed. The commits it named stay
              stored; only the reference goes. A tag a release names cannot be deleted.
            </>
          )}
        </Notice>
      </Box>
    </ModalDialog>
  );
};

export default ConfirmDeleteDialog;
