import { Box, Heading, Text } from "@primer/react";

import IdentityAvatar from "../common/IdentityAvatar";
import { formatAbsoluteTime, formatRelativeTime } from "../../utils/dates";

/**
 * What this repository is made of, from `/insights`.
 *
 * Every figure here is counted by the engine itself — commits walked from HEAD,
 * refs, paths in the tree, objects in the store — so the panel says what the
 * repository actually contains rather than what a host would normally advertise
 * about it.
 *
 * The reference also shows stars, forks, watchers, a language breakdown, topics
 * and a licence. None of those exists: nothing counts stars or forks, no
 * language is detected, and a LICENSE file is a file rather than parsed
 * metadata. They are omitted rather than filled with plausible numbers.
 */
const RepositoryMeta = ({ insights, repository }) => {
  if (!insights) return null;

  const figures = [
    { label: "Commits", value: insights.commits },
    { label: "Branches", value: insights.branches },
    { label: "Files", value: insights.files },
    { label: "Objects", value: insights.storedObjects },
  ];

  const contributors = insights.contributors ?? [];

  return (
    <Box sx={{ display: "grid", gap: 3 }}>
      <Box
        sx={{
          border: "1px solid",
          borderColor: "border.default",
          borderRadius: 2,
          bg: "canvas.subtle",
          p: 3,
        }}
      >
        <Heading as="h2" sx={{ fontSize: 1, fontWeight: 600, mb: 3 }}>
          About
        </Heading>

        <Box sx={{ display: "grid", gap: 2 }}>
          {figures.map(({ label, value }) => (
            <Box
              key={label}
              sx={{ display: "flex", alignItems: "baseline", justifyContent: "space-between", gap: 3 }}
            >
              <Text sx={{ fontSize: 0, color: "fg.muted" }}>{label}</Text>
              <Text sx={{ fontSize: 1, fontWeight: 600, fontFamily: "mono" }}>
                {/* A count of zero is a fact; a count still loading is not, and
                    `0` would state something the server has not said yet. */}
                {typeof value === "number" ? value.toLocaleString() : "—"}
              </Text>
            </Box>
          ))}
        </Box>

        {/* The two dates the repository record has carried all along and the
            overview never showed. Relative to read at a glance, exact in the
            title for when the glance is not enough. */}
        {repository && (
          <Box sx={{ display: "grid", gap: 2, mt: 3, pt: 3, borderTop: "1px solid", borderColor: "border.muted" }}>
            <Timestamp label="Created" at={repository.createdAt} />
            <Timestamp label="Updated" at={repository.updatedAt} />
          </Box>
        )}
      </Box>

      {contributors.length > 0 && (
        <Box
          sx={{
            border: "1px solid",
            borderColor: "border.default",
            borderRadius: 2,
            bg: "canvas.subtle",
            p: 3,
          }}
        >
          <Heading as="h2" sx={{ fontSize: 1, fontWeight: 600, mb: 3 }}>
            Contributors
          </Heading>

          <Box as="ul" sx={{ listStyle: "none", m: 0, p: 0, display: "grid", gap: 2 }}>
            {contributors.map((person) => (
              <Box
                as="li"
                key={person.email ?? person.name}
                sx={{ display: "flex", alignItems: "center", gap: 2, minWidth: 0 }}
              >
                <IdentityAvatar username={person.name} size={20} />
                <Text
                  sx={{
                    fontSize: 0,
                    minWidth: 0,
                    overflow: "hidden",
                    textOverflow: "ellipsis",
                    whiteSpace: "nowrap",
                  }}
                >
                  {person.name}
                </Text>
                <Box sx={{ flex: 1, minWidth: 0 }} />
                <Text sx={{ fontSize: 0, color: "fg.subtle", flexShrink: 0, fontFamily: "mono" }}>
                  {person.commits}
                </Text>
              </Box>
            ))}
          </Box>
        </Box>
      )}
    </Box>
  );
};

/** A date the reader can take in, with the precise one a hover away. */
const Timestamp = ({ label, at }) => {
  if (!at) return null;

  return (
    <Box sx={{ display: "flex", alignItems: "baseline", justifyContent: "space-between", gap: 3 }}>
      <Text sx={{ fontSize: 0, color: "fg.muted" }}>{label}</Text>
      <Text sx={{ fontSize: 0, color: "fg.default" }} title={formatAbsoluteTime(at)}>
        {formatRelativeTime(at)}
      </Text>
    </Box>
  );
};

export default RepositoryMeta;
