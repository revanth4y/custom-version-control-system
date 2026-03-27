import { useNavigate } from "react-router-dom";
import { Box, Heading, Text } from "@primer/react";

import PageContainer from "../components/layout/PageContainer";
import IssueForm from "../components/issues/IssueForm";
import Notice from "../components/common/Notice";
import { useMutation } from "../hooks/useMutation";
import { useAuth } from "../hooks/useAuth";
import { useRepository } from "../hooks/useRepository";
import { issueService } from "../services/issueService";
import { canParticipate } from "../utils/issues";

/**
 * Filing a new issue.
 *
 * A page rather than a dialog: the body can run to twenty thousand characters,
 * and composing that in a modal is a worse experience than the modal saves.
 */
const NewIssue = () => {
  const { owner, name } = useRepository();
  const { currentUser } = useAuth();
  const navigate = useNavigate();
  const { run, pending, error } = useMutation();

  const submit = async ({ title, body }) => {
    const result = await run(
      () => issueService.create(owner, name, { title, body }),
      "The issue could not be created.",
    );
    if (result.ok) navigate(`/${owner}/${name}/issues/${result.value.number}`);
  };

  return (
    <PageContainer width="medium">
      <Box sx={{ mb: 3 }}>
        <Heading as="h2" sx={{ fontSize: 3, fontWeight: 600, mb: 1 }}>
          New issue
        </Heading>
        <Text sx={{ fontSize: 1, color: "fg.muted" }}>
          In {owner}/{name}.
        </Text>
      </Box>

      {canParticipate(currentUser) ? (
        <Box
          sx={{
            border: "1px solid",
            borderColor: "border.default",
            borderRadius: 2,
            bg: "canvas.subtle",
            p: [3, 4],
          }}
        >
          <IssueForm
            submitLabel="Create issue"
            pendingLabel="Creating..."
            pending={pending}
            error={error}
            onSubmit={submit}
            onCancel={() => navigate(`/${owner}/${name}/issues`)}
          />
        </Box>
      ) : (
        <Notice variant="info">Sign in to open an issue on this repository.</Notice>
      )}
    </PageContainer>
  );
};

export default NewIssue;
