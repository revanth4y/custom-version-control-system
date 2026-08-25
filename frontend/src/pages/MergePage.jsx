import { useCallback, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import RouterLink from "../components/common/RouterLink";
import { Box, Button, Heading, Link, Text, Spinner } from "@primer/react";
import Octicon from "../components/common/Octicon";
import { ArrowLeftIcon, GitMergeIcon } from "@primer/octicons-react";

import PageContainer from "../components/layout/PageContainer";
import { AsyncBoundary } from "../components/common/states";
import Notice from "../components/common/Notice";
import ModalDialog from "../components/common/ModalDialog";
import BranchSelector from "../components/branch/BranchSelector";
import BranchMeta from "../components/branch/BranchMeta";
import MergeOutcome from "../components/merge/MergeOutcome";
import { useBranches } from "../hooks/useBranches";
import { useMutation } from "../hooks/useMutation";
import { useRepository } from "../hooks/useRepository";
import { mergeService } from "../services/mergeService";
import { movedTheBranch, resultFrom, validateMerge } from "../utils/merge";

/**
 * Merging one branch into another.
 *
 * Both sides live in the query string so a proposed merge is a link. The
 * engine's answer is shown exactly as given, including a conflict - which is a
 * real answer, not a failure, and leaves the repository untouched.
 *
 * Choosing branches here never moves HEAD. Merging changes the target branch's
 * reference and nothing else; which branch the repository is "on" is a separate
 * decision made elsewhere.
 */
const MergePage = () => {
  const { owner, name, head, canWrite, reloadHead } = useRepository();
  const [params, setParams] = useSearchParams();

  const { branches, loading, error, reload } = useBranches(owner, name);
  const { run, pending, error: mergeError, clearError } = useMutation();

  const [result, setResult] = useState(null);
  const [confirming, setConfirming] = useState(false);

  const defaultTarget = head?.branch ?? "";
  const target = params.get("into") || defaultTarget;
  const source = params.get("from") || "";

  const tipOf = useCallback(
    (branchName) => branches.find((branch) => branch.name === branchName),
    [branches],
  );

  const problem = useMemo(
    () => validateMerge({ target, source, branches }),
    [target, source, branches],
  );

  const setSide = (key) => (value) => {
    const next = new URLSearchParams(params);
    next.set(key, value);
    setParams(next);
    // A previous answer describes the previous pair, so it stops applying.
    setResult(null);
    clearError();
  };

  const performMerge = async () => {
    setConfirming(false);
    const outcome = await run(
      () => mergeService.merge(owner, name, { ourBranch: target, theirBranch: source }),
      "The merge could not be attempted.",
      // A 409 carries the conflict result; it is an answer, not a failure.
      resultFrom,
    );
    if (outcome.ok) {
      setResult(outcome.value);
      reload();
      // A fast-forward or merge may have moved the branch the repository is on.
      if (movedTheBranch(outcome.value.outcome) && target === head?.branch) reloadHead();
    } else if (outcome.recovered) {
      setResult(outcome.recovered);
    }
  };

  return (
    <PageContainer>
      <Box sx={{ mb: 3 }}>
        <Link as={RouterLink} to={`/${owner}/${name}/branches`} sx={{ fontSize: 0 }}>
          <Octicon icon={ArrowLeftIcon} size={12} sx={{ mr: 1 }} />
          Branches
        </Link>
        <Heading as="h2" sx={{ fontSize: 3, fontWeight: 600, mt: 2, mb: 1 }}>
          Merge branches
        </Heading>
        <Text sx={{ fontSize: 1, color: "fg.muted" }}>
          Bring the work on one branch into another. Only the target branch changes.
        </Text>
      </Box>

      <AsyncBoundary
        loading={loading}
        error={error}
        onRetry={reload}
        loadingLabel="Loading branches"
        minHeight="200px"
      >
        <Box
          sx={{
            border: "1px solid",
            borderColor: "border.default",
            borderRadius: 2,
            bg: "canvas.subtle",
            p: 3,
            mb: 3,
          }}
        >
          <Box
            sx={{
              display: "grid",
              gridTemplateColumns: ["1fr", "1fr", "1fr 1fr"],
              gap: 3,
            }}
          >
            <SideChooser
              label="target"
              hint="ours — this branch will change"
              value={target}
              tip={tipOf(target)}
              owner={owner}
              name={name}
              head={head}
              canWrite={canWrite}
              onChange={setSide("into")}
              onHeadChanged={reloadHead}
            />
            <SideChooser
              label="source"
              hint="theirs — this branch is only read"
              value={source}
              tip={tipOf(source)}
              owner={owner}
              name={name}
              head={head}
              canWrite={canWrite}
              onChange={setSide("from")}
              onHeadChanged={reloadHead}
              placeholder="Choose a branch"
            />
          </Box>

          {problem && (
            <Box sx={{ mt: 3 }}>
              <Notice variant="info">{problem}</Notice>
            </Box>
          )}

          {mergeError && (
            <Box sx={{ mt: 3 }}>
              <Notice variant="danger">{mergeError}</Notice>
            </Box>
          )}

          {canWrite ? (
            <Box sx={{ mt: 3, display: "flex", alignItems: "center", gap: 3, flexWrap: "wrap" }}>
              <Button
                variant="primary"
                leadingVisual={pending ? undefined : GitMergeIcon}
                disabled={Boolean(problem) || pending}
                onClick={() => setConfirming(true)}
              >
                {pending ? (
                  <Box sx={{ display: "inline-flex", alignItems: "center", gap: 2 }}>
                    <Spinner size="small" />
                    Merging
                  </Box>
                ) : (
                  "Merge"
                )}
              </Button>
              {!problem && (
                <Text sx={{ fontSize: 0, color: "fg.muted" }}>
                  <Text as="span" sx={{ fontFamily: "mono" }}>{target}</Text> will change;{" "}
                  <Text as="span" sx={{ fontFamily: "mono" }}>{source}</Text> will not.
                </Text>
              )}
            </Box>
          ) : (
            <Box sx={{ mt: 3 }}>
              <Notice variant="info">
                Only the repository owner can merge. You can still compare these branches and read
                what separates them.
              </Notice>
            </Box>
          )}
        </Box>

        {!problem && !result && (
          <Box sx={{ mb: 3 }}>
            <Link
              as={RouterLink}
              to={`/${owner}/${name}/compare?base=${encodeURIComponent(target)}&head=${encodeURIComponent(source)}`}
              sx={{ fontSize: 0 }}
            >
              See what separates these branches first
            </Link>
          </Box>
        )}

        {result && (
          <MergeOutcome
            result={result}
            owner={owner}
            name={name}
            target={target}
            source={source}
          />
        )}
      </AsyncBoundary>

      {confirming && (
        <ConfirmMerge
          target={target}
          source={source}
          targetTip={tipOf(target)}
          sourceTip={tipOf(source)}
          onCancel={() => setConfirming(false)}
          onConfirm={performMerge}
        />
      )}
    </PageContainer>
  );
};

const SideChooser = ({
  label,
  hint,
  value,
  tip,
  owner,
  name,
  head,
  canWrite,
  onChange,
  onHeadChanged,
  placeholder,
}) => (
  <Box sx={{ minWidth: 0 }}>
    <Text sx={{ display: "block", fontSize: 0, color: "fg.subtle" }}>{label}</Text>
    <Text sx={{ display: "block", fontSize: 0, color: "fg.muted", mb: 2 }}>{hint}</Text>
    <BranchSelector
      owner={owner}
      name={name}
      currentRef={value || placeholder || "none"}
      headBranch={head?.branch}
      canWrite={canWrite}
      onRefChange={onChange}
      onHeadChanged={onHeadChanged}
    />
    <Box sx={{ mt: 2, minWidth: 0 }}>{tip ? <BranchMeta tip={tip.tip} showAuthor /> : null}</Box>
  </Box>
);

/**
 * Confirming before the write.
 *
 * States plainly which reference is about to move and to what, because the
 * merge is the one action on this page that changes the repository.
 */
const ConfirmMerge = ({ target, source, targetTip, sourceTip, onCancel, onConfirm }) => (
  <ModalDialog
    title="Merge these branches?"
    role="alertdialog"
    onClose={onCancel}
    actions={
      <>
        <Button onClick={onCancel}>Cancel</Button>
        <Button variant="primary" onClick={onConfirm}>
          Merge
        </Button>
      </>
    }
  >
    <Box sx={{ display: "flex", flexDirection: "column", gap: 3 }}>
      <TipRow label="target" branch={target} tip={targetTip} note="will change" />
      <TipRow label="source" branch={source} tip={sourceTip} note="will not change" />
      <Notice variant="warning">
        This moves the <Text as="span" sx={{ fontFamily: "mono" }}>{target}</Text> branch reference.
        Commits are never rewritten, and nothing is deleted.
      </Notice>
    </Box>
  </ModalDialog>
);

const TipRow = ({ label, branch, tip, note }) => (
  <Box
    sx={{
      border: "1px solid",
      borderColor: "border.default",
      borderRadius: 2,
      bg: "canvas.inset",
      p: 2,
      minWidth: 0,
    }}
  >
    <Box sx={{ display: "flex", alignItems: "baseline", gap: 2, flexWrap: "wrap", mb: 1 }}>
      <Text sx={{ fontSize: 0, color: "fg.subtle" }}>{label}</Text>
      <Text sx={{ fontFamily: "mono", fontWeight: 600, overflowWrap: "anywhere", minWidth: 0 }}>
        {branch}
      </Text>
      <Text sx={{ fontSize: 0, color: "fg.muted" }}>{note}</Text>
    </Box>
    {tip ? <BranchMeta tip={tip.tip} /> : null}
  </Box>
);

export default MergePage;
