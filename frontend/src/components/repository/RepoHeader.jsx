import { Link as RouterLink } from "react-router-dom";
import { Box, Heading, Text, Label, Link, Octicon } from "@primer/react";
import { RepoIcon, LockIcon } from "@primer/octicons-react";

import { PAGE_GUTTER, PAGE_MAX_WIDTHS } from "../layout/pageBounds";

/**
 * Identity strip at the top of every repository page.
 *
 * Deliberately dense: it repeats on every tab, so each extra line it occupies is
 * a line taken from the content someone actually came to read. Owner and name
 * are separate links because both are useful destinations.
 */
const RepoHeader = ({ repository }) => {
  const isPrivate = repository.visibility === "PRIVATE";

  return (
    <Box
      sx={{
        bg: "canvas.subtle",
        borderBottom: "1px solid",
        borderColor: "border.default",
        pt: 3,
      }}
    >
      {/* The gutter belongs inside the capped column, not outside it. With the
          padding on the full-bleed element the cap was reached 24px sooner, and
          the repository name sat that far left of the brand above it and the
          listing below. Same bounds as the app shell and every page, from one
          place, so the three cannot drift apart again. */}
      <Box
        sx={{
          maxWidth: PAGE_MAX_WIDTHS.large,
          mx: "auto",
          px: PAGE_GUTTER,
        }}
      >
        <Box sx={{ display: "flex", alignItems: "baseline", gap: 2, flexWrap: "wrap", mb: 1 }}>
          {/* The title is one inline text flow rather than a row of flex items.
              As flex children, the icon, owner and name each wrapped on their
              own — a long name pushed the icon onto a line by itself and split
              the owner mid-word. Inline, the whole title reflows as a sentence
              and only breaks between its parts. */}
          <Heading
            as="h1"
            sx={{
              fontSize: 3,
              fontWeight: 400,
              minWidth: 0,
              lineHeight: 1.25,
              // Only a single unbroken word too long for the line is split; the
              // alternative is a horizontal scrollbar on the whole page.
              overflowWrap: "anywhere",
            }}
          >
            <Octicon
              icon={isPrivate ? LockIcon : RepoIcon}
              sx={{ color: "fg.muted", mr: 2, verticalAlign: "baseline" }}
            />
            <Link as={RouterLink} to={`/${repository.ownerUsername}`} sx={{ color: "accent.fg" }}>
              {repository.ownerUsername}
            </Link>
            <Text sx={{ color: "fg.muted", mx: 1 }}>/</Text>
            <Link
              as={RouterLink}
              to={`/${repository.ownerUsername}/${repository.name}`}
              sx={{ color: "accent.fg", fontWeight: 600 }}
            >
              {repository.name}
            </Link>
          </Heading>

          <Label sx={{ color: "fg.muted", borderColor: "border.default", flexShrink: 0 }}>
            {isPrivate ? "Private" : "Public"}
          </Label>
        </Box>

        {repository.description && (
          <Text as="p" sx={{ color: "fg.muted", fontSize: 1, mt: 0, mb: 2, maxWidth: "80ch" }}>
            {repository.description}
          </Text>
        )}
      </Box>
    </Box>
  );
};

export default RepoHeader;
