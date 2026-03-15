import { useRef, useState } from "react";
import { Box, Button, FormControl, Select, Spinner, Text, TextInput } from "@primer/react";

import ModalDialog from "../common/ModalDialog";
import Notice from "../common/Notice";
import { useMutation } from "../../hooks/useMutation";
import { branchService } from "../../services/branchService";
import { MAX_BRANCH_NAME_LENGTH, validateBranchName } from "../../utils/branchName";

/**
 * Creating a branch from an existing revision.
 *
 * The name is checked here as it is typed, but the server checks it again and
 * decides: only it knows whether the name is already taken or whether the start
 * point still resolves. Client validation catches the obvious cases early; the
 * server's own refusal is shown verbatim when it disagrees.
 */
const CreateBranchDialog = ({ owner, name, branches, defaultStartPoint, onClose, onCreated }) => {
  const [branchName, setBranchName] = useState("");
  const [startPoint, setStartPoint] = useState(defaultStartPoint ?? "HEAD");
  const [touched, setTouched] = useState(false);

  const nameInput = useRef(null);
  const { run, pending, error, clearError } = useMutation();

  const localProblem = validateBranchName(branchName);
  // Not shown until the field has been used, so the dialog does not open
  // already complaining about an empty box.
  const shownProblem = touched ? localProblem : null;

  const submit = async () => {
    setTouched(true);
    if (localProblem) {
      nameInput.current?.focus();
      return;
    }

    const result = await run(
      () => branchService.create(owner, name, { name: branchName.trim(), startPoint }),
      "The branch could not be created.",
    );
    if (result.ok) onCreated(result.value);
  };

  return (
    <ModalDialog
      title="New branch"
      description="A branch is a movable name for a commit. Creating one copies nothing."
      onClose={onClose}
      initialFocusRef={nameInput}
      actions={
        <>
          <Button onClick={onClose} disabled={pending}>
            Cancel
          </Button>
          <Button
            variant="primary"
            onClick={submit}
            disabled={pending || branchName.trim() === ""}
          >
            {pending ? "Creating..." : "Create branch"}
          </Button>
        </>
      }
    >
      <Box sx={{ display: "flex", flexDirection: "column", gap: 3 }}>
        {error && <Notice variant="danger">{error}</Notice>}

        <FormControl required>
          <FormControl.Label>Branch name</FormControl.Label>
          <TextInput
            ref={nameInput}
            block
            value={branchName}
            maxLength={MAX_BRANCH_NAME_LENGTH}
            placeholder="feature/login"
            aria-invalid={shownProblem ? "true" : undefined}
            onChange={(event) => {
              setBranchName(event.target.value);
              clearError();
            }}
            onBlur={() => setTouched(true)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                event.preventDefault();
                submit();
              }
            }}
          />
          {shownProblem ? (
            <FormControl.Validation variant="error">{shownProblem}</FormControl.Validation>
          ) : (
            <FormControl.Caption>
              Slashes group branches, so feature/login and bugfix/auth/token are both valid.
            </FormControl.Caption>
          )}
        </FormControl>

        <FormControl>
          <FormControl.Label>Start point</FormControl.Label>
          <Select
            block
            value={startPoint}
            onChange={(event) => {
              setStartPoint(event.target.value);
              clearError();
            }}
          >
            <Select.Option value="HEAD">HEAD (the repository&apos;s current branch)</Select.Option>
            {branches.map((branch) => (
              <Select.Option key={branch.name} value={branch.name}>
                {branch.name}
              </Select.Option>
            ))}
          </Select>
          <FormControl.Caption>
            The new branch will point at this revision&apos;s commit.
          </FormControl.Caption>
        </FormControl>

        {pending && (
          <Box sx={{ display: "flex", alignItems: "center", gap: 2 }}>
            <Spinner size="small" />
            <Text sx={{ fontSize: 0, color: "fg.muted" }}>Writing the reference...</Text>
          </Box>
        )}
      </Box>
    </ModalDialog>
  );
};

export default CreateBranchDialog;
