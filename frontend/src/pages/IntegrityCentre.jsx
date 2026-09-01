import { Box, Button, Heading, Label, Text } from "@primer/react";
import {
  AlertIcon,
  ShieldCheckIcon,
  ShieldIcon,
  ShieldXIcon,
  SyncIcon,
} from "@primer/octicons-react";

import PageContainer from "../components/layout/PageContainer";
import Octicon from "../components/common/Octicon";
import { EmptyState, ErrorState, LoadingState } from "../components/common/states";
import { useAsync } from "../hooks/useAsync";
import { useRepository } from "../hooks/useRepository";
import { integrityService } from "../services/integrityService";
import { formatAbsoluteTime } from "../utils/dates";

/**
 * How each way an object can fail reads to someone looking at the list.
 *
 * The server sends a closed set of reasons, so this is a translation rather than
 * a guess. An unrecognised value falls back to the raw code instead of being
 * dropped: a reason this build has not heard of is still worth showing.
 */
const REASON_LABELS = {
  HASH_MISMATCH: "hash mismatch",
  UNREADABLE: "unreadable",
  MISSING: "missing",
};

/**
 * Whether the repository's stored objects still hash to their ids.
 *
 * <strong>The verification is the server's.</strong> An object's id is the SHA-1
 * of its canonical framed form, and those bytes are not exposed by any endpoint,
 * so the browser cannot check a hash and this page never suggests it did. What is
 * shown is what the server reported after reading each object back and hashing it
 * again.
 *
 * The check runs only when asked. It is the one read in the application whose
 * cost scales with how much the repository holds rather than with the size of the
 * answer, and an explicit action is also what makes "checked at" mean anything.
 *
 * Care is taken over what is claimed. Passing means the stored bytes still match
 * the ids they are filed under - not that the history is complete or correct, and
 * not, when nothing was verified, that anything is sound at all.
 */
const IntegrityCentre = () => {
  const { owner, name, head } = useRepository();

  const check = useAsync(
    () => integrityService.forRepository(owner, name),
    [owner, name],
    { immediate: false },
  );

  const report = check.data;
  const hasHistory = Boolean(head?.commit);
  const idle = !check.loading && !check.error && !report;

  return (
    <PageContainer>
      <Box sx={{ mb: 3 }}>
        <Heading as="h2" sx={{ fontSize: 3, fontWeight: 600, mb: 1 }}>
          Integrity
        </Heading>
        <Text sx={{ fontSize: 1, color: "fg.muted" }}>
          Every object is stored under the SHA-1 of its own contents. This reads each one back and
          hashes it again to check it still matches.
        </Text>
      </Box>

      {!hasHistory ? (
        <Panel>
          <EmptyState
            icon={ShieldIcon}
            title="Nothing to verify"
            message="This repository has no commits, so there are no stored objects to check."
            minHeight="220px"
          />
        </Panel>
      ) : (
        <Box sx={{ display: "flex", flexDirection: "column", gap: 3 }}>
          <Box sx={{ display: "flex", flexWrap: "wrap", alignItems: "center", gap: 2 }}>
            <Button
              leadingVisual={check.loading ? SyncIcon : ShieldCheckIcon}
              onClick={check.reload}
              disabled={check.loading}
              sx={{ "&:focus-visible": { outline: "2px solid", outlineColor: "accent.fg", outlineOffset: "2px" } }}
            >
              {report || check.error ? "Run check again" : "Run check"}
            </Button>
            {idle && (
              <Text sx={{ fontSize: 0, color: "fg.subtle" }}>
                Nothing has been verified yet.
              </Text>
            )}
          </Box>

          {/* Polite rather than assertive: the outcome is worth announcing when
              it arrives, but it is not an interruption. */}
          <Box aria-live="polite">
            {check.loading && <Panel><LoadingState label="Re-reading and re-hashing stored objects" minHeight="220px" /></Panel>}

            {check.error && !check.loading && (
              <Panel>
                <ErrorState
                  title="The check could not be run"
                  message={check.error}
                  onRetry={check.reload}
                  minHeight="220px"
                />
              </Panel>
            )}

            {report && !check.loading && !check.error && <Report report={report} />}
          </Box>
        </Box>
      )}
    </PageContainer>
  );
};

/** The outcome, once the server has actually looked. */
const Report = ({ report }) => {
  const damaged = report.damaged ?? [];
  const nothingVerified = report.verified === 0;

  if (nothingVerified) {
    return (
      <Panel>
        <EmptyState
          icon={ShieldIcon}
          title="Nothing to verify"
          message="This repository holds no stored objects, so nothing was checked."
          minHeight="220px"
        />
      </Panel>
    );
  }

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 3 }}>
      <Verdict report={report} damaged={damaged} />
      {damaged.length > 0 && <DamagedList damaged={damaged} />}
    </Box>
  );
};

/**
 * The headline, stated as narrowly as the evidence allows.
 *
 * The wording is deliberate: objects were re-hashed and matched their ids. It
 * does not say the repository is correct, complete, or undamaged in any wider
 * sense, because a scan of stored objects cannot establish that.
 */
const Verdict = ({ report, damaged }) => {
  const healthy = report.healthy === true;
  const objects = `${report.verified} stored ${report.verified === 1 ? "object" : "objects"}`;

  return (
    <Panel>
      <Box sx={{ px: 3, py: 3, display: "flex", gap: 3, alignItems: "flex-start" }}>
        <Octicon
          icon={healthy ? ShieldCheckIcon : ShieldXIcon}
          size={20}
          sx={{ color: healthy ? "success.fg" : "danger.fg", flexShrink: 0, mt: "2px" }}
        />
        <Box sx={{ minWidth: 0 }}>
          {/* The state is named in words as well as drawn, so it does not depend
              on a colour or an icon being perceived. */}
          <Box sx={{ display: "flex", flexWrap: "wrap", alignItems: "center", gap: 2, mb: 1 }}>
            <Label variant={healthy ? "success" : "danger"}>{healthy ? "Verified" : "Damaged"}</Label>
            <Text sx={{ fontSize: 0, color: "fg.subtle" }}>
              checked {formatAbsoluteTime(report.checkedAt)} · {report.durationMs} ms
            </Text>
          </Box>

          <Text sx={{ display: "block", fontSize: 1, fontWeight: 600 }}>
            {healthy
              ? `All ${objects} re-hashed to the ids they are filed under.`
              : `${damaged.length} of ${objects} did not match the id it is filed under.`}
          </Text>

          <Text sx={{ display: "block", fontSize: 0, color: "fg.muted", mt: 2 }}>
            This checks that stored bytes still hash to their ids. It does not establish that the
            history is complete or that nothing is missing.
          </Text>

          {report.truncated && (
            <Box sx={{ display: "flex", gap: 2, alignItems: "flex-start", mt: 2 }}>
              <Octicon icon={AlertIcon} size={14} sx={{ color: "attention.fg", flexShrink: 0, mt: "3px" }} />
              <Text sx={{ fontSize: 0, color: "attention.fg" }}>
                Only the first {report.verified} of {report.storedObjects} stored objects were
                checked, so this result describes part of the repository rather than all of it.
              </Text>
            </Box>
          )}
        </Box>
      </Box>
    </Panel>
  );
};

/** Every object that failed, named in full so it can be looked up. */
const DamagedList = ({ damaged }) => (
  <Box sx={{ minWidth: 0 }}>
    <Heading as="h3" sx={{ fontSize: 2, fontWeight: 600, mb: 2 }}>
      Damaged objects
    </Heading>
    <Panel>
      {damaged.map((object, index) => (
        <Box
          key={object.id}
          sx={{
            px: 3,
            py: 3,
            minWidth: 0,
            borderTop: index === 0 ? "none" : "1px solid",
            borderColor: "border.muted",
          }}
        >
          <Box sx={{ display: "flex", flexWrap: "wrap", alignItems: "center", gap: 2, mb: 1 }}>
            <Label variant="danger">{REASON_LABELS[object.reason] ?? object.reason}</Label>
            <Text sx={{ fontSize: 0, color: "fg.muted" }}>{object.detail}</Text>
          </Box>
          {/* Full, and allowed to break anywhere: a 40-character id must not push
              the page sideways on a narrow screen. */}
          <Text sx={{ display: "block", fontFamily: "mono", fontSize: 0, wordBreak: "break-all", minWidth: 0 }}>
            {object.id}
          </Text>
        </Box>
      ))}
    </Panel>
  </Box>
);

const Panel = ({ children }) => (
  <Box
    sx={{
      bg: "canvas.subtle",
      border: "1px solid",
      borderColor: "border.default",
      borderRadius: 2,
      overflow: "hidden",
      minWidth: 0,
    }}
  >
    {children}
  </Box>
);

export default IntegrityCentre;
