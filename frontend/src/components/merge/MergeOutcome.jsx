import { Link as RouterLink } from "react-router-dom";
import { Box, Heading, Link, Text, Octicon } from "@primer/react";
import {
  AlertIcon,
  CheckCircleIcon,
  GitMergeIcon,
  ArrowRightIcon,
  InfoIcon,
} from "@primer/octicons-react";

import ConflictList from "./ConflictList";
import { Outcome } from "../../utils/merge";

/**
 * What the merge actually did, as the backend reported it.
 *
 * The four outcomes are genuinely different events and are told apart plainly:
 * a fast-forward moved a pointer and created nothing, a merge created a commit
 * with two parents, an up-to-date merge did nothing at all, and a conflict
 * changed no reference and wrote no object. Nothing here infers a result - the
 * response says which happened.
 */
const TONE = {
  [Outcome.ALREADY_UP_TO_DATE]: { icon: InfoIcon, color: "fg.muted", border: "border.default", bg: "canvas.overlay" },
  [Outcome.FAST_FORWARDED]: { icon: ArrowRightIcon, color: "success.fg", border: "success.muted", bg: "success.subtle" },
  [Outcome.MERGED]: { icon: GitMergeIcon, color: "success.fg", border: "success.muted", bg: "success.subtle" },
  [Outcome.CONFLICTED]: { icon: AlertIcon, color: "attention.fg", border: "attention.muted", bg: "attention.subtle" },
};

const TITLE = {
  [Outcome.ALREADY_UP_TO_DATE]: "Already up to date",
  [Outcome.FAST_FORWARDED]: "Fast-forwarded",
  [Outcome.MERGED]: "Merged",
  [Outcome.CONFLICTED]: "Could not merge",
};

const MergeOutcome = ({ result, owner, name, target, source }) => {
  const tone = TONE[result.outcome] ?? TONE[Outcome.ALREADY_UP_TO_DATE];

  return (
    <Box
      sx={{
        border: "1px solid",
        borderColor: tone.border,
        borderRadius: 2,
        bg: "canvas.subtle",
        overflow: "hidden",
      }}
    >
      <Box
        sx={{
          display: "flex",
          alignItems: "center",
          gap: 2,
          px: 3,
          py: 3,
          bg: tone.bg,
          borderBottom: "1px solid",
          borderColor: tone.border,
        }}
      >
        <Octicon icon={tone.icon} size={18} sx={{ color: tone.color, flexShrink: 0 }} />
        <Heading as="h3" sx={{ fontSize: 2, fontWeight: 600 }}>
          {TITLE[result.outcome] ?? result.outcome}
        </Heading>
      </Box>

      <Box sx={{ px: 3, py: 3 }}>
        <Body result={result} owner={owner} name={name} target={target} source={source} />
      </Box>
    </Box>
  );
};

const Body = ({ result, owner, name, target, source }) => {
  const branchLink = (branch) => (
    <Link as={RouterLink} to={`/${owner}/${name}/tree/${encodeURIComponent(branch)}`} sx={{ fontFamily: "mono" }}>
      {branch}
    </Link>
  );
  const commitLink = (sha) => (
    <Link as={RouterLink} to={`/${owner}/${name}/commit/${sha}`} sx={{ fontFamily: "mono" }} title={sha}>
      {sha.slice(0, 12)}
    </Link>
  );

  if (result.outcome === Outcome.ALREADY_UP_TO_DATE) {
    return (
      <Text sx={{ fontSize: 1, color: "fg.muted" }}>
        {branchLink(target)} already contains everything on {branchLink(source)}. Nothing was
        written and no reference moved.
      </Text>
    );
  }

  if (result.outcome === Outcome.FAST_FORWARDED) {
    return (
      <>
        <Text sx={{ fontSize: 1, color: "fg.muted", display: "block", mb: 3 }}>
          {branchLink(target)} had no commits of its own, so it moved straight to the tip of{" "}
          {branchLink(source)}. <Text as="span" sx={{ color: "fg.default" }}>No merge commit was created</Text> —
          there was nothing to reconcile.
        </Text>
        <Facts>
          <Fact label={`${target} now points at`}>{commitLink(result.head)}</Fact>
        </Facts>
        <Actions>
          <Link as={RouterLink} to={`/${owner}/${name}/commit/${result.head}`}>Open that commit</Link>
          <Link as={RouterLink} to={`/${owner}/${name}/commits/${encodeURIComponent(target)}`}>
            View the branch history
          </Link>
        </Actions>
      </>
    );
  }

  if (result.outcome === Outcome.MERGED) {
    return (
      <>
        <Text sx={{ fontSize: 1, color: "fg.muted", display: "block", mb: 3 }}>
          Both branches had changed, so a merge commit was created on {branchLink(target)} with two
          parents: its previous tip and the tip of {branchLink(source)}.
        </Text>
        <Facts>
          <Fact label="merge commit">{commitLink(result.mergeCommit)}</Fact>
          <Fact label={`${target} now points at`}>{commitLink(result.head)}</Fact>
          <Fact label="resulting tree">
            <Text sx={{ fontFamily: "mono", fontSize: 0, color: "fg.muted" }} title={result.tree}>
              {result.tree?.slice(0, 12)}
            </Text>
          </Fact>
        </Facts>
        <Actions>
          <Link as={RouterLink} to={`/${owner}/${name}/commit/${result.mergeCommit}`}>
            Open the merge commit
          </Link>
          <Link as={RouterLink} to={`/${owner}/${name}/tree/${encodeURIComponent(target)}`}>
            Browse {target}
          </Link>
        </Actions>
      </>
    );
  }

  return (
    <>
      <Text sx={{ fontSize: 1, color: "fg.muted", display: "block", mb: 3 }}>
        {branchLink(target)} was <Text as="span" sx={{ color: "fg.default" }}>not changed</Text>. The
        engine wrote no objects and moved no reference; these paths have to be reconciled before the
        merge can be recorded.
      </Text>

      <ConflictList
        conflicts={result.conflicts ?? []}
        owner={owner}
        name={name}
        target={target}
        source={source}
      />

      {(result.cleanlyMerged?.length ?? 0) > 0 && (
        <Box sx={{ mt: 4 }}>
          <Heading as="h4" sx={{ fontSize: 1, fontWeight: 600, mb: 1 }}>
            Would have merged cleanly
          </Heading>
          <Text sx={{ fontSize: 0, color: "fg.muted", display: "block", mb: 2 }}>
            These paths the engine could reconcile. They were not written, because a merge is
            recorded whole or not at all.
          </Text>
          <Box sx={{ display: "flex", flexDirection: "column", gap: 1 }}>
            {result.cleanlyMerged.map((change) => (
              <Box key={change.path} sx={{ display: "flex", alignItems: "center", gap: 2 }}>
                <Octicon icon={CheckCircleIcon} size={12} sx={{ color: "success.fg", flexShrink: 0 }} />
                <Text sx={{ fontFamily: "mono", fontSize: 0, overflowWrap: "anywhere" }}>
                  {change.path}
                </Text>
                <Text sx={{ fontSize: 0, color: "fg.subtle" }}>{change.type?.toLowerCase()}</Text>
              </Box>
            ))}
          </Box>
        </Box>
      )}
    </>
  );
};

const Facts = ({ children }) => (
  <Box sx={{ display: "flex", flexDirection: ["column", "row"], flexWrap: "wrap", gap: [2, 4], mb: 3 }}>
    {children}
  </Box>
);

const Fact = ({ label, children }) => (
  <Box sx={{ minWidth: 0 }}>
    <Text sx={{ display: "block", fontSize: 0, color: "fg.subtle", mb: 1 }}>{label}</Text>
    <Box sx={{ fontSize: 0 }}>{children}</Box>
  </Box>
);

const Actions = ({ children }) => (
  <Box sx={{ display: "flex", gap: 3, flexWrap: "wrap", fontSize: 0 }}>{children}</Box>
);

export default MergeOutcome;
