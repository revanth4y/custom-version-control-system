import { useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Box,
  Heading,
  Text,
  Button,
  FormControl,
  TextInput,
  Textarea,
  Radio,
  RadioGroup,
} from "@primer/react";
import Octicon from "../components/common/Octicon";
import { RepoIcon, LockIcon } from "@primer/octicons-react";

import PageContainer from "../components/layout/PageContainer";
import Notice from "../components/common/Notice";
import { useAuth } from "../hooks/useAuth";
import { repoService } from "../services/repoService";
import { errorMessage } from "../services/api";
import {
  MAX_DESCRIPTION_LENGTH,
  validateRepositoryName,
} from "../utils/repoName";

/**
 * Creating a repository.
 *
 * Validation is duplicated from the server deliberately: the server remains the
 * authority and its errors are surfaced verbatim, but catching a malformed name
 * before submitting turns a round trip into instant feedback. The client is a
 * convenience, never the gate.
 */
const CreateRepository = () => {
  const { currentUser } = useAuth();
  const navigate = useNavigate();

  const [form, setForm] = useState({ name: "", description: "", visibility: "PUBLIC" });
  const [touched, setTouched] = useState(false);
  const [error, setError] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  const nameProblem = validateRepositoryName(form.name);
  const showNameProblem = touched && nameProblem;

  const update = (field) => (event) =>
    setForm((current) => ({ ...current, [field]: event.target.value }));

  const handleSubmit = async (event) => {
    event.preventDefault();
    setTouched(true);
    if (nameProblem) return;

    setError(null);
    setSubmitting(true);

    try {
      const created = await repoService.create({
        name: form.name.trim(),
        description: form.description.trim() || null,
        visibility: form.visibility,
      });
      // Straight to the new repository: creating one is almost always the first
      // step of doing something in it.
      navigate(`/${created.ownerUsername}/${created.name}`, { replace: true });
    } catch (caught) {
      setError(errorMessage(caught, "Could not create the repository."));
      setSubmitting(false);
    }
  };

  return (
    <PageContainer width="medium">
      <Box sx={{ mb: 4 }}>
        <Heading as="h1" sx={{ fontSize: 4, fontWeight: 600, mb: 1 }}>
          Create a repository
        </Heading>
        <Text sx={{ color: "fg.muted", fontSize: 1 }}>
          A repository holds your files and their whole history.
        </Text>
      </Box>

      <Box
        as="form"
        onSubmit={handleSubmit}
        sx={{
          display: "grid",
          gap: 4,
          bg: "canvas.subtle",
          border: "1px solid",
          borderColor: "border.default",
          borderRadius: 2,
          p: [3, 4],
        }}
      >
        {error && <Notice variant="danger">{error}</Notice>}

        <FormControl required>
          <FormControl.Label>Repository name</FormControl.Label>
          <Box sx={{ display: "flex", alignItems: "center", gap: 2, flexWrap: "wrap" }}>
            <Text sx={{ color: "fg.muted", fontSize: 1, flexShrink: 0 }}>
              {currentUser.username} /
            </Text>
            <Box sx={{ flex: 1, minWidth: "200px" }}>
              <TextInput
                value={form.name}
                onChange={update("name")}
                onBlur={() => setTouched(true)}
                placeholder="my-project"
                validationStatus={showNameProblem ? "error" : undefined}
                autoFocus
                block
              />
            </Box>
          </Box>
          {showNameProblem ? (
            <FormControl.Validation variant="error">{nameProblem}</FormControl.Validation>
          ) : (
            <FormControl.Caption>
              Letters, digits, and “.”, “_” or “-”. Must start with a letter or digit.
            </FormControl.Caption>
          )}
        </FormControl>

        <FormControl>
          <FormControl.Label>Description</FormControl.Label>
          <Textarea
            value={form.description}
            onChange={update("description")}
            placeholder="What is this repository for?"
            rows={3}
            maxLength={MAX_DESCRIPTION_LENGTH}
            block
          />
          <FormControl.Caption>
            Optional. {MAX_DESCRIPTION_LENGTH - form.description.length} characters left.
          </FormControl.Caption>
        </FormControl>

        <RadioGroup
          name="visibility"
          onChange={(value) => setForm((current) => ({ ...current, visibility: value }))}
        >
          <RadioGroup.Label>Visibility</RadioGroup.Label>
          <FormControl>
            <Radio value="PUBLIC" checked={form.visibility === "PUBLIC"} />
            <FormControl.Label>
              <VisibilityLabel icon={RepoIcon} title="Public" description="Anyone can see this repository." />
            </FormControl.Label>
          </FormControl>
          <FormControl>
            <Radio value="PRIVATE" checked={form.visibility === "PRIVATE"} />
            <FormControl.Label>
              <VisibilityLabel icon={LockIcon} title="Private" description="Only you can see this repository." />
            </FormControl.Label>
          </FormControl>
        </RadioGroup>

        <Box sx={{ display: "flex", justifyContent: "flex-end", gap: 2, flexWrap: "wrap" }}>
          <Button type="button" onClick={() => navigate("/")} disabled={submitting}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" disabled={submitting || Boolean(nameProblem)}>
            {submitting ? "Creating…" : "Create repository"}
          </Button>
        </Box>
      </Box>
    </PageContainer>
  );
};

const VisibilityLabel = ({ icon, title, description }) => (
  <Box sx={{ display: "flex", alignItems: "flex-start", gap: 2 }}>
    <Octicon icon={icon} sx={{ color: "fg.muted", mt: "2px" }} />
    <Box>
      <Text sx={{ display: "block", fontWeight: 600 }}>{title}</Text>
      <Text sx={{ display: "block", fontSize: 0, color: "fg.muted", fontWeight: 400 }}>
        {description}
      </Text>
    </Box>
  </Box>
);

export default CreateRepository;
