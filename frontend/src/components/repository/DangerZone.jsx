import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { Box, Button, FormControl, Heading, Text, TextInput } from "@primer/react";

import ModalDialog from "../common/ModalDialog";
import Notice from "../common/Notice";
import { repoService } from "../../services/repoService";
import { errorMessage } from "../../services/api";
import { confirmsDeletion } from "../../utils/repositorySettings";

/**
 * Deleting a repository, and the friction that ought to precede it.
 *
 * The name has to be typed exactly. That is not ceremony: this is the only
 * action here that cannot be undone, and it takes the commits, the objects and
 * every issue with it. A button on its own is too easy to press by accident,
 * and a yes/no dialog is barely harder.
 */
const DangerZone = ({ owner, repository }) => {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [typed, setTyped] = useState("");
  const [deleting, setDeleting] = useState(false);
  const [error, setError] = useState(null);

  const confirmed = confirmsDeletion(repository.name, typed);

  const close = () => {
    if (deleting) return;
    setOpen(false);
    setTyped("");
    setError(null);
  };

  const remove = async () => {
    if (!confirmed) return;

    setDeleting(true);
    setError(null);
    try {
      await repoService.remove(repository.id);

      // There is nothing left to return to. Replacing keeps the back button off
      // a repository that no longer exists.
      navigate(`/${owner}`, { replace: true });
    } catch (caught) {
      setError(errorMessage(caught, "Could not delete the repository."));
      setDeleting(false);
    }
  };

  return (
    <Box
      as="section"
      sx={{
        border: "1px solid",
        borderColor: "danger.emphasis",
        borderRadius: 2,
        bg: "canvas.subtle",
        p: [3, 4],
      }}
    >
      <Heading as="h3" sx={{ fontSize: 2, fontWeight: 600, mb: 1, color: "danger.fg" }}>
        Danger zone
      </Heading>
      <Text as="p" sx={{ color: "fg.muted", fontSize: 1, mt: 0, mb: 3 }}>
        Deleting this repository removes its history, its contents and its issues. This cannot be
        undone.
      </Text>

      <Button variant="danger" onClick={() => setOpen(true)}>
        Delete this repository
      </Button>

      {open && (
        <ModalDialog title="Delete this repository?" onClose={close}>
          <Box sx={{ display: "grid", gap: 3 }}>
            {error && <Notice variant="danger">{error}</Notice>}

            <Text as="p" sx={{ fontSize: 1, m: 0 }}>
              This deletes <strong>{owner}/{repository.name}</strong>, everything committed to it,
              and every issue raised against it. It cannot be undone.
            </Text>

            <FormControl>
              <FormControl.Label>
                Type <strong>{repository.name}</strong> to confirm
              </FormControl.Label>
              <TextInput
                value={typed}
                onChange={(event) => setTyped(event.target.value)}
                aria-label={`Type ${repository.name} to confirm deletion`}
                autoComplete="off"
                autoFocus
                block
              />
            </FormControl>

            <Box sx={{ display: "flex", justifyContent: "flex-end", gap: 2, flexWrap: "wrap" }}>
              <Button type="button" onClick={close} disabled={deleting}>
                Cancel
              </Button>
              <Button variant="danger" onClick={remove} disabled={!confirmed || deleting}>
                {deleting ? "Deleting…" : "Delete repository"}
              </Button>
            </Box>
          </Box>
        </ModalDialog>
      )}
    </Box>
  );
};

export default DangerZone;
