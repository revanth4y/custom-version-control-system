import { useRef, useState } from "react";
import { Box, Button, FormControl, Text, TextInput, Textarea } from "@primer/react";

import Notice from "../common/Notice";

/** Mirrors CreateIssueRequest and UpdateIssueRequest on the server. */
export const MAX_TITLE = 200;
export const MAX_BODY = 20_000;

function validateTitle(raw) {
  const title = (raw ?? "").trim();
  if (!title) return "A title is required.";
  if (title.length > MAX_TITLE) return `Must be ${MAX_TITLE} characters or fewer.`;
  return null;
}

/**
 * Writing an issue, whether new or being edited.
 *
 * One form for both, because the fields and the rules are the same; only the
 * words on the button differ. The server validates again and its message is
 * shown verbatim when it disagrees.
 */
const IssueForm = ({
  initialTitle = "",
  initialBody = "",
  submitLabel,
  pendingLabel,
  pending,
  error,
  onSubmit,
  onCancel,
  autoFocus = true,
}) => {
  const [title, setTitle] = useState(initialTitle);
  const [body, setBody] = useState(initialBody);
  const [touched, setTouched] = useState(false);
  const titleInput = useRef(null);

  const problem = validateTitle(title);
  const shown = touched ? problem : null;

  const submit = (event) => {
    event?.preventDefault();
    setTouched(true);
    if (problem) {
      titleInput.current?.focus();
      return;
    }
    onSubmit({ title: title.trim(), body });
  };

  return (
    <Box as="form" onSubmit={submit} sx={{ display: "flex", flexDirection: "column", gap: 3 }}>
      {error && <Notice variant="danger">{error}</Notice>}

      <FormControl required>
        <FormControl.Label>Title</FormControl.Label>
        <TextInput
          ref={titleInput}
          block
          autoFocus={autoFocus}
          value={title}
          maxLength={MAX_TITLE}
          placeholder="Something short and specific"
          aria-invalid={shown ? "true" : undefined}
          onChange={(event) => setTitle(event.target.value)}
          onBlur={() => setTouched(true)}
        />
        {shown ? (
          <FormControl.Validation variant="error">{shown}</FormControl.Validation>
        ) : (
          <FormControl.Caption>{MAX_TITLE - title.trim().length} characters left.</FormControl.Caption>
        )}
      </FormControl>

      <FormControl>
        <FormControl.Label>Description</FormControl.Label>
        <Textarea
          block
          rows={10}
          value={body}
          maxLength={MAX_BODY}
          placeholder="Markdown is supported. What happened, and what did you expect?"
          onChange={(event) => setBody(event.target.value)}
          sx={{ fontFamily: "mono", fontSize: 0 }}
        />
        <FormControl.Caption>Optional. Rendered as Markdown.</FormControl.Caption>
      </FormControl>

      <Box sx={{ display: "flex", gap: 2, flexWrap: "wrap" }}>
        <Button type="submit" variant="primary" disabled={pending || title.trim() === ""}>
          {pending ? pendingLabel : submitLabel}
        </Button>
        {onCancel && (
          <Button type="button" onClick={onCancel} disabled={pending}>
            Cancel
          </Button>
        )}
        <Text sx={{ fontSize: 0, color: "fg.subtle", alignSelf: "center" }}>
          {body.length > MAX_BODY - 500 ? `${MAX_BODY - body.length} characters left` : ""}
        </Text>
      </Box>
    </Box>
  );
};

export default IssueForm;
