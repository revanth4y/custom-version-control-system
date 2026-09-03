import { Box, Text } from "@primer/react";
import { PeopleIcon } from "@primer/octicons-react";

import IdentityAvatar from "../common/IdentityAvatar";
import RangeControls from "./RangeControls";
import { AsyncBoundary, EmptyState } from "../common/states";
import { count, percent } from "./format";

/**
 * Who wrote the commits in the chosen window.
 *
 * Identity is the email address, which is how the engine groups authorship: one
 * person may commit under several display names, but the address is what
 * identifies them. The address shown is the one already recorded in the commit
 * and already returned by the existing insights endpoint — this view does not
 * widen who can see it.
 *
 * The percentage is of the commits in this window, not of the repository, so it
 * always sums to a hundred across the list on screen.
 */
const ContributorsTab = ({ range, contributors }) => {
  const data = contributors.data;
  const rows = data?.contributors ?? [];
  const windowTotal = rows.reduce((sum, person) => sum + person.commits, 0);

  return (
    <Box>
      <RangeControls
        from={range.draft.from}
        to={range.draft.to}
        onFrom={range.setFrom}
        onTo={range.setTo}
        onApply={range.apply}
        onReset={range.reset}
        pending={contributors.loading}
      />

      <AsyncBoundary
        loading={contributors.loading}
        error={contributors.error}
        onRetry={contributors.reload}
        loadingLabel="Loading contributors"
        minHeight="240px"
      >
        {data &&
          (rows.length === 0 ? (
            <EmptyState
              icon={PeopleIcon}
              title="No commits in this period"
              message="Nobody authored a commit between the selected dates."
              minHeight="220px"
            />
          ) : (
            <Box
              sx={{
                border: "1px solid",
                borderColor: "border.default",
                borderRadius: 2,
                overflow: "hidden",
              }}
            >
              <Box
                sx={{
                  px: 3,
                  py: 2,
                  bg: "canvas.subtle",
                  borderBottom: "1px solid",
                  borderColor: "border.default",
                  display: "flex",
                  justifyContent: "space-between",
                  gap: 2,
                  flexWrap: "wrap",
                }}
              >
                <Text sx={{ fontSize: 1, fontWeight: 600 }}>
                  {count(data.total)} {data.total === 1 ? "contributor" : "contributors"}
                </Text>
                <Text sx={{ fontSize: 0, color: "fg.muted" }}>
                  {data.from} to {data.to}
                </Text>
              </Box>

              {/* Already sorted by commit count server-side; the order is preserved. */}
              {rows.map((person) => (
                <Box
                  key={person.email}
                  sx={{
                    display: "flex",
                    alignItems: "center",
                    gap: 3,
                    px: 3,
                    py: 3,
                    borderTop: "1px solid",
                    borderColor: "border.muted",
                    ":first-of-type": { borderTop: 0 },
                    flexWrap: "wrap",
                  }}
                >
                  <IdentityAvatar username={person.email} size={32} />

                  <Box sx={{ flex: "1 1 200px", minWidth: 0 }}>
                    <Text sx={{ fontSize: 1, fontWeight: 600, display: "block", wordBreak: "break-word" }}>
                      {person.name}
                    </Text>
                    <Text sx={{ fontSize: 0, color: "fg.muted", display: "block", wordBreak: "break-all" }}>
                      {person.email}
                    </Text>
                  </Box>

                  <Box sx={{ minWidth: "120px" }}>
                    <Text sx={{ fontSize: 1, fontWeight: 600, display: "block" }}>
                      {count(person.commits)} {person.commits === 1 ? "commit" : "commits"}
                    </Text>
                    <Text sx={{ fontSize: 0, color: "fg.muted" }}>
                      {percent(person.commits, windowTotal)}
                      {person.merges > 0 ? ` · ${count(person.merges)} merges` : ""}
                    </Text>
                  </Box>

                  <Box sx={{ minWidth: "150px" }}>
                    <Text sx={{ fontSize: 0, color: "fg.muted", display: "block" }}>
                      Last commit {person.lastCommit ?? "—"}
                    </Text>
                    <Text sx={{ fontSize: 0, color: "fg.subtle", display: "block" }}>
                      First {person.firstCommit ?? "—"}
                    </Text>
                  </Box>
                </Box>
              ))}
            </Box>
          ))}
      </AsyncBoundary>
    </Box>
  );
};

export default ContributorsTab;
