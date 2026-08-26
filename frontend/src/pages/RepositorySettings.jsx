import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Box,
  Button,
  FormControl,
  Heading,
  Radio,
  RadioGroup,
  Text,
  TextInput,
  Textarea,
} from "@primer/react";

import PageContainer from "../components/layout/PageContainer";
import Notice from "../components/common/Notice";
import DangerZone from "../components/repository/DangerZone";
import { ErrorState, LoadingState } from "../components/common/states";
import { useRepository } from "../hooks/useRepository";
import { repoService } from "../services/repoService";
import { errorMessage } from "../services/api";
import { MAX_DESCRIPTION_LENGTH, validateRepositoryName } from "../utils/repoName";
import {
  VISIBILITY_OPTIONS,
  descriptionUpdate,
  hasChanged,
} from "../utils/repositorySettings";

/**
 * What an owner can change about a repository.
 *
 * Three sections that save independently, because they are three decisions:
 * renaming moves every link to the repository, clearing a description does not,
 * and going private takes it away from everyone else. One "Save" over all of
 * them would make the smallest of those as heavy as the largest.
 *
 * The gate here is presentation. The server refuses a stranger's PATCH whether
 * or not this page renders, and reports an unreadable repository as missing
 * before it mentions permission at all — so nothing below is what keeps a
 * repository safe.
 */
const RepositorySettings = () => {
  const { owner, repository, loading, canWrite, reload } = useRepository();

  if (loading && !repository) {
    return <LoadingState label="Loading settings" minHeight="40vh" />;
  }

  if (!canWrite) {
    return (
      <PageContainer>
        <ErrorState
          title="Not yours to change"
          message="Only the owner of a repository can change its settings."
          minHeight="40vh"
        />
      </PageContainer>
    );
  }

  return (
    <PageContainer>
      {/* h2, not h1: the repository header above owns the page's only h1, which
          is the convention every other repository tab follows. */}
      <Heading as="h2" sx={{ fontSize: 3, fontWeight: 400, mb: 4 }}>
        Settings
      </Heading>

      <Box sx={{ display: "grid", gap: 4, maxWidth: "760px" }}>
        <RenameSection owner={owner} repository={repository} />
        <DescriptionSection repository={repository} onSaved={reload} />
        <VisibilitySection repository={repository} onSaved={reload} />
        <DangerZone owner={owner} repository={repository} />
      </Box>
    </PageContainer>
  );
};

/** A titled panel, matching the surfaces the overview already uses. */
const Section = ({ title, description, children }) => (
  <Box
    as="section"
    sx={{
      bg: "canvas.subtle",
      border: "1px solid",
      borderColor: "border.default",
      borderRadius: 2,
      p: [3, 4],
    }}
  >
    <Heading as="h3" sx={{ fontSize: 2, fontWeight: 600, mb: description ? 1 : 3 }}>
      {title}
    </Heading>
    {description && (
      <Text as="p" sx={{ color: "fg.muted", fontSize: 1, mt: 0, mb: 3 }}>
        {description}
      </Text>
    )}
    {children}
  </Box>
);

const RenameSection = ({ owner, repository }) => {
  const navigate = useNavigate();
  const [value, setValue] = useState(repository.name);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);

  // The field follows the repository when it changes underneath — after a
  // rename, or when the provider remounts on a different repository.
  useEffect(() => setValue(repository.name), [repository.name]);

  const problem = validateRepositoryName(value);
  const changed = hasChanged(repository.name, value);

  const submit = async (event) => {
    event.preventDefault();
    if (!changed || problem) return;

    setSaving(true);
    setError(null);
    try {
      const updated = await repoService.update(repository.id, { name: value.trim() });

      /* Every URL for this repository has just changed. Replacing rather than
         pushing keeps the back button off the old one, which is now a 404. The
         provider is keyed by owner and name, so this remounts it rather than
         leaving it describing a repository under its previous name. */
      navigate(`/${owner}/${updated.name}/settings`, { replace: true });
    } catch (caught) {
      // A name already taken comes back as a 409 with the server's own wording,
      // which is more specific than anything guessable here.
      setError(errorMessage(caught, "Could not rename the repository."));
      setSaving(false);
    }
  };

  return (
    <Section
      title="Repository name"
      description="Renaming changes every link to this repository. Its history and contents are unaffected."
    >
      <Box as="form" onSubmit={submit} sx={{ display: "grid", gap: 3 }}>
        {error && <Notice variant="danger">{error}</Notice>}

        <FormControl>
          <FormControl.Label>Name</FormControl.Label>
          <Box sx={{ display: "flex", alignItems: "center", gap: 2, flexWrap: "wrap" }}>
            <Text sx={{ color: "fg.muted", fontSize: 1, flexShrink: 0 }}>{owner} /</Text>
            <Box sx={{ flex: 1, minWidth: "200px" }}>
              <TextInput
                value={value}
                onChange={(event) => setValue(event.target.value)}
                validationStatus={changed && problem ? "error" : undefined}
                aria-label="Repository name"
                block
              />
            </Box>
          </Box>
          {changed && problem ? (
            <FormControl.Validation variant="error">{problem}</FormControl.Validation>
          ) : (
            <FormControl.Caption>
              Letters, digits, and “.”, “_” or “-”. Must start with a letter or digit.
            </FormControl.Caption>
          )}
        </FormControl>

        <Box sx={{ display: "flex", justifyContent: "flex-end" }}>
          <Button type="submit" disabled={saving || !changed || Boolean(problem)}>
            {saving ? "Renaming…" : "Rename"}
          </Button>
        </Box>
      </Box>
    </Section>
  );
};

const DescriptionSection = ({ repository, onSaved }) => {
  const [value, setValue] = useState(repository.description ?? "");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => setValue(repository.description ?? ""), [repository.description]);

  const update = descriptionUpdate(repository.description, value);

  const submit = async (event) => {
    event.preventDefault();
    if (!update) return;

    setSaving(true);
    setError(null);
    try {
      // `update` carries an empty string when the description is being cleared,
      // which is not the same to the server as leaving the field out.
      await repoService.update(repository.id, update);
      onSaved();
    } catch (caught) {
      setError(errorMessage(caught, "Could not save the description."));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Section title="Description" description="Shown beneath the repository name and in listings.">
      <Box as="form" onSubmit={submit} sx={{ display: "grid", gap: 3 }}>
        {error && <Notice variant="danger">{error}</Notice>}

        <FormControl>
          <FormControl.Label visuallyHidden>Description</FormControl.Label>
          <Textarea
            value={value}
            onChange={(event) => setValue(event.target.value)}
            placeholder="What is this repository for?"
            rows={3}
            maxLength={MAX_DESCRIPTION_LENGTH}
            aria-label="Description"
            block
          />
          <FormControl.Caption>
            Optional. {MAX_DESCRIPTION_LENGTH - value.length} characters left.
          </FormControl.Caption>
        </FormControl>

        <Box sx={{ display: "flex", justifyContent: "flex-end" }}>
          <Button type="submit" disabled={saving || !update}>
            {saving ? "Saving…" : "Save"}
          </Button>
        </Box>
      </Box>
    </Section>
  );
};

const VisibilitySection = ({ repository, onSaved }) => {
  const [value, setValue] = useState(repository.visibility);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => setValue(repository.visibility), [repository.visibility]);

  const changed = value !== repository.visibility;

  const submit = async (event) => {
    event.preventDefault();
    if (!changed) return;

    setSaving(true);
    setError(null);
    try {
      await repoService.update(repository.id, { visibility: value });
      onSaved();
    } catch (caught) {
      setError(errorMessage(caught, "Could not change the visibility."));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Section title="Visibility">
      <Box as="form" onSubmit={submit} sx={{ display: "grid", gap: 3 }}>
        {error && <Notice variant="danger">{error}</Notice>}

        <RadioGroup name="visibility" onChange={(next) => setValue(next)}>
          <RadioGroup.Label visuallyHidden>Visibility</RadioGroup.Label>
          {VISIBILITY_OPTIONS.map((option) => (
            <FormControl key={option.value}>
              <Radio value={option.value} checked={value === option.value} />
              <FormControl.Label>{option.label}</FormControl.Label>
              <FormControl.Caption>{option.description}</FormControl.Caption>
            </FormControl>
          ))}
        </RadioGroup>

        <Box sx={{ display: "flex", justifyContent: "flex-end" }}>
          <Button type="submit" disabled={saving || !changed}>
            {saving ? "Saving…" : "Save"}
          </Button>
        </Box>
      </Box>
    </Section>
  );
};

export default RepositorySettings;
