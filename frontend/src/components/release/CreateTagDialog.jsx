import { useRef, useState } from "react";
import { Box, Button, FormControl, Textarea, TextInput } from "@primer/react";

import ModalDialog from "../common/ModalDialog";
import Notice from "../common/Notice";
import { useMutation } from "../../hooks/useMutation";
import { releaseService } from "../../services/releaseService";

/**
 * Creating a tag against a revision.
 *
 * There is no annotated/lightweight switch. Writing a message produces an
 * annotated tag and leaving it empty produces a lightweight one, which is the
 * same rule the API uses — an annotated tag with nothing to say and a
 * lightweight tag are the same thing.
 *
 * The name is checked here as it is typed, but the server checks it again and
 * decides: only it knows whether the name is taken or whether the target still
 * resolves.
 */
const CreateTagDialog = ({ owner, name, defaultTarget, onClose, onCreated }) => {
  const [tagName, setTagName] = useState("");
  const [target, setTarget] = useState(defaultTarget ?? "HEAD");
  const [message, setMessage] = useState("");

  const nameInput = useRef(null);
  const { run, pending, error, clearError } = useMutation();

  const submit = async () => {
    if (tagName.trim() === "") {
      nameInput.current?.focus();
      return;
    }
    const result = await run(
      () =>
        releaseService.createTag(owner, name, {
          name: tagName.trim(),
          target: target.trim() || "HEAD",
          message: message.trim() === "" ? undefined : message,
        }),
      "The tag could not be created.",
    );
    if (result.ok) onCreated(result.value);
  };

  return (
    <ModalDialog
      title="New tag"
      description="A tag names a point in history permanently. It cannot be moved afterwards."
      onClose={onClose}
      initialFocusRef={nameInput}
      actions={
        <>
          <Button onClick={onClose} disabled={pending}>
            Cancel
          </Button>
          <Button variant="primary" onClick={submit} disabled={pending || tagName.trim() === ""}>
            {pending ? "Creating..." : "Create tag"}
          </Button>
        </>
      }
    >
      <Box sx={{ display: "flex", flexDirection: "column", gap: 3 }}>
        {error && <Notice variant="danger">{error}</Notice>}

        <FormControl required>
          <FormControl.Label>Tag name</FormControl.Label>
          <TextInput
            ref={nameInput}
            block
            value={tagName}
            maxLength={255}
            placeholder="v1.0.0"
            onChange={(event) => {
              setTagName(event.target.value);
              clearError();
            }}
          />
          <FormControl.Caption>
            Slashes group tags, so release/v1.0 is valid. A tag cannot be renamed or moved.
          </FormControl.Caption>
        </FormControl>

        <FormControl>
          <FormControl.Label>Target</FormControl.Label>
          <TextInput
            block
            value={target}
            maxLength={255}
            placeholder="HEAD"
            onChange={(event) => {
              setTarget(event.target.value);
              clearError();
            }}
          />
          <FormControl.Caption>
            A branch, another tag, HEAD, or a commit id.
          </FormControl.Caption>
        </FormControl>

        <FormControl>
          <FormControl.Label>Message</FormControl.Label>
          <Textarea
            block
            rows={4}
            value={message}
            onChange={(event) => {
              setMessage(event.target.value);
              clearError();
            }}
          />
          <FormControl.Caption>
            Optional. A message makes this an annotated tag, stored as an object with
            your name and the time. Leave it empty for a lightweight tag.
          </FormControl.Caption>
        </FormControl>
      </Box>
    </ModalDialog>
  );
};

export default CreateTagDialog;
