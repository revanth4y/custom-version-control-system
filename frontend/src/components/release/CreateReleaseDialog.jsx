import { useRef, useState } from "react";
import { Box, Button, Checkbox, FormControl, Select, Textarea, TextInput } from "@primer/react";

import ModalDialog from "../common/ModalDialog";
import Notice from "../common/Notice";
import { useMutation } from "../../hooks/useMutation";
import { releaseService } from "../../services/releaseService";

/**
 * Publishing a release against an existing tag.
 *
 * The tag is chosen from those that exist rather than typed, because a release
 * must name one that already does — offering a free-text box would invite a
 * request the server can only refuse.
 */
const CreateReleaseDialog = ({ owner, name, tags, onClose, onCreated }) => {
  const available = tags ?? [];

  const [tag, setTag] = useState(available[0]?.name ?? "");
  const [title, setTitle] = useState("");
  const [body, setBody] = useState("");
  const [draft, setDraft] = useState(false);
  const [prerelease, setPrerelease] = useState(false);

  const titleInput = useRef(null);
  const { run, pending, error, clearError } = useMutation();

  const submit = async () => {
    if (title.trim() === "" || tag === "") {
      titleInput.current?.focus();
      return;
    }
    const result = await run(
      () =>
        releaseService.create(owner, name, {
          tag,
          name: title.trim(),
          body,
          draft,
          prerelease,
        }),
      "The release could not be created.",
    );
    if (result.ok) onCreated(result.value);
  };

  return (
    <ModalDialog
      title="Draft a release"
      description="A release is a note attached to a tag. Deleting it later leaves the tag in place."
      onClose={onClose}
      initialFocusRef={titleInput}
      actions={
        <>
          <Button onClick={onClose} disabled={pending}>
            Cancel
          </Button>
          <Button
            variant="primary"
            onClick={submit}
            disabled={pending || title.trim() === "" || tag === ""}
          >
            {pending ? "Saving..." : draft ? "Save draft" : "Publish release"}
          </Button>
        </>
      }
    >
      <Box sx={{ display: "flex", flexDirection: "column", gap: 3 }}>
        {error && <Notice variant="danger">{error}</Notice>}

        {available.length === 0 ? (
          <Notice variant="warning">
            This repository has no tags yet. Create one first — a release has to name a tag.
          </Notice>
        ) : (
          <FormControl required>
            <FormControl.Label>Tag</FormControl.Label>
            <Select
              block
              value={tag}
              onChange={(event) => {
                setTag(event.target.value);
                clearError();
              }}
            >
              {available.map((candidate) => (
                <Select.Option key={candidate.name} value={candidate.name}>
                  {candidate.name}
                </Select.Option>
              ))}
            </Select>
            <FormControl.Caption>
              A release names one tag, and cannot be re-pointed afterwards.
            </FormControl.Caption>
          </FormControl>
        )}

        <FormControl required>
          <FormControl.Label>Title</FormControl.Label>
          <TextInput
            ref={titleInput}
            block
            value={title}
            maxLength={255}
            placeholder="Version 1.0"
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
          <FormControl.Label>Save as a draft</FormControl.Label>
          <FormControl.Caption>Only you can see a draft until it is published.</FormControl.Caption>
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

export default CreateReleaseDialog;
