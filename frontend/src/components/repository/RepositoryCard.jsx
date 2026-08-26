import RouterLink from "../common/RouterLink";
import { Box, Label, Link, Text } from "@primer/react";
import Octicon from "../common/Octicon";
import { CodeIcon, LockIcon } from "@primer/octicons-react";

import { formatRelativeTime } from "../../utils/dates";
import { descriptionOf, isPrivate, repositoryPath, visibilityLabel } from "../../utils/repositories";

/**
 * One repository, as the reference draws it.
 *
 * The dashboard and the profile page each used to render their own version of
 * this row, and the two had drifted: different name sizes, one showing a badge
 * only for private repositories, one capitalising "Updated" and the other not.
 * Both now render this, so the same object cannot look like two things.
 *
 * The reference card also carries a language dot, a star count and a fork
 * count. None of the three exists — the API returns name, description,
 * visibility, owner and two timestamps, and nothing else — so they are left
 * out rather than invented. The row they sit on is kept, with the timestamp
 * still pushed to its right edge, so the card holds the reference's proportions
 * instead of collapsing to fit what happens to be available today.
 */
const RepositoryCard = ({ repo, headingLevel = "h3" }) => {
  const description = descriptionOf(repo);
  const isFirstParty = isPrivate(repo);

  return (
    <Box
      sx={{
        display: "flex",
        alignItems: "flex-start",
        gap: 3,
        p: 3,
        minWidth: 0,
        "&:hover": { bg: "canvas.inset" },
      }}
    >
      {/* The tile the reference sets the glyph on, rather than a bare icon. */}
      <Box
        sx={{
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          width: "32px",
          height: "32px",
          flexShrink: 0,
          borderRadius: 2,
          bg: "canvas.inset",
          border: "1px solid",
          borderColor: "border.muted",
          color: "fg.muted",
        }}
      >
        <Octicon icon={isFirstParty ? LockIcon : CodeIcon} size={16} />
      </Box>

      <Box sx={{ minWidth: 0, flex: 1 }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 2, flexWrap: "wrap" }}>
          <Link
            as={RouterLink}
            to={repositoryPath(repo)}
            sx={{ fontSize: 1, fontWeight: 600, overflowWrap: "anywhere" }}
          >
            <Box as={headingLevel} sx={{ fontSize: "inherit", fontWeight: "inherit", m: 0 }}>
              {repo.name}
            </Box>
          </Link>

          {/* The secondary variant borders with border.muted, which is too dark
              against the row surface to read as a pill at this size. */}
          <Label sx={{ color: "fg.muted", borderColor: "border.default", flexShrink: 0 }}>
            {visibilityLabel(repo)}
          </Label>
        </Box>

        {description && (
          <Text
            as="p"
            sx={{
              fontSize: 0,
              color: "fg.muted",
              mt: 1,
              mb: 0,
              /* Two lines, as the reference clamps it. A long description
                 otherwise sets the height of every card beside it. */
              display: "-webkit-box",
              WebkitLineClamp: 2,
              WebkitBoxOrient: "vertical",
              overflow: "hidden",
            }}
          >
            {description}
          </Text>
        )}

        <Box
          sx={{
            display: "flex",
            alignItems: "center",
            justifyContent: "flex-end",
            gap: 3,
            mt: 2,
            minHeight: "20px",
          }}
        >
          <Text sx={{ fontSize: 0, color: "fg.subtle" }}>
            Updated {formatRelativeTime(repo.updatedAt)}
          </Text>
        </Box>
      </Box>
    </Box>
  );
};

export default RepositoryCard;
