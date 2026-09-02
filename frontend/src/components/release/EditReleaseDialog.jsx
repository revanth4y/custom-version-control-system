import { useRef, useState } from "react";
import { Box, Button, Checkbox, FormControl, Textarea, TextInput } from "@primer/react";

import ModalDialog from "../common/ModalDialog";
import Notice from "../common/Notice";
import { useMutation } from "../../hooks/useMutation";
import { releaseService } from "../../services/releaseService";

/**
 * Editing what a release says.
 *
 * There is deliberately no tag field. A release cannot be re-pointed: a
 * published note that quietly came to describe different code would be exactly
 * the failure immutable tags exist to prevent, so the tag appears as read-only
 * context rather than as something to change.
 */
const EditReleaseDialog = ({ owner, name, release, onClose, onSaved }) => {
  const [title, setTitle] = useState(release.name ?? "");
  const [body, setBody] = useState(release.body ?? "");
  const [draft, setDraft] = useState(release.draft);
  const [prerelease, setPrerelease] = useState(release.prerelease);

  const titleInput = useRef(null);
  const { run, pending, error, clearError } = useMutation();

  const submit = async () => {
    if (title.trim() === "") {
      titleInput.current?.focus();
      return;
    }
    const result = await run(
      () =>
        releaseService.update(owner, name, release.id, {
          name: title.trim(),
          body,
          draft,
          prerelease,
        }),
      "The release could not be updated.",
    );
    if (result.ok) onSaved(result.value);
  };

  return (
    <ModalDialog
      title="Edit release"
      description={"Tag " + release.tag + ". A release cannot be moved to another tag."}
      onClose={onClose}
      initialFocusRef={titleInput}
      actions={
        <>
          <Button onClick={onClose} disabled={pending}>
            Cancel
          </Button>
          <Button variant="primary" onClick={submit} disabled={pending || title.trim() === ""}>
            {pending ? "Saving..." : "Save changes"}
          </Button>
        </>
      }
    >
      <Box sx={{ display: "flex", flexDirection: "column", gap: 3 }}>
        {error && <Notice variant="danger">{error}</Notice>}

        <FormControl required>
          <FormControl.Label>Title</FormControl.Label>
          <TextInput
            ref={titleInput}
            block
            value={title}
            maxLength={255}
            onChange={(event) => {
              setTitle(event.target.value);
              clearError();
            }}
          />
        </FormControl>

        <FormControl>
          <FormControl.Label>Notes</FormControl.Label>
          <Textarea
            block
            rows={6}
            value={body}
            onChange={(event) => {
              setBody(event.target.value);
              clearError();
            }}
          />
        </FormControl>

        <FormControl>
          <Checkbox checked={draft} onChange={(event) => setDraft(event.target.checked)} />
          <FormControl.Label>Keep as a draft</FormControl.Label>
          <FormControl.Caption>
            Publishing stamps the moment it went out. Returning to draft clears that stamp.
          </FormControl.Caption>
        </FormControl>

        <FormControl>
          <Checkbox
            checked={prerelease}
            onChange={(event) => setPrerelease(event.target.checked)}
          />
          <FormControl.Label>Mark as a pre-release</FormControl.Label>
        </FormControl>
      </Box>
    </ModalDialog>
  );
};

export default EditReleaseDialog;
