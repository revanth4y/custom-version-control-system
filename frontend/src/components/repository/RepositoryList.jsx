import { Link as RouterLink } from "react-router-dom";
import { Box, Heading, Link, Octicon, Text } from "@primer/react";
import { ArrowRightIcon, PlusIcon } from "@primer/octicons-react";

import RepositoryCard from "./RepositoryCard";

/**
 * The panel the reference draws around a set of repositories: a title with an
 * action beside it, the cards, and a way to add one at the foot.
 *
 * Both the header action and the footer action are optional, because neither is
 * always truthful. "View all" belongs on the dashboard, where the list is a
 * selection of a longer one; on a profile it would point at the page already
 * being read. "New repository" belongs where the viewer can actually create
 * one.
 *
 * The list is a `ul`. Screen-reader users get the count announced and can jump
 * the whole block, which a stack of divs does not offer.
 */
const RepositoryList = ({ repositories, title, viewAll, onCreateHref, headingLevel = "h2" }) => (
  <Box
    sx={{
      border: "1px solid",
      borderColor: "border.default",
      borderRadius: 2,
      bg: "canvas.subtle",
      overflow: "hidden",
    }}
  >
    {title && (
      <Box
        sx={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          gap: 3,
          px: 3,
          py: 2,
          borderBottom: "1px solid",
          borderColor: "border.muted",
        }}
      >
        <Heading as={headingLevel} sx={{ fontSize: 1, fontWeight: 600 }}>
          {title}
        </Heading>

        {viewAll && (
          <Link
            as={RouterLink}
            to={viewAll.to}
            sx={{ fontSize: 0, display: "inline-flex", alignItems: "center", gap: 1 }}
          >
            {viewAll.label ?? "View all"}
            <Octicon icon={ArrowRightIcon} size={12} />
          </Link>
        )}
      </Box>
    )}

    <Box as="ul" sx={{ listStyle: "none", m: 0, p: 0 }}>
      {repositories.map((repo, index) => (
        <Box
          as="li"
          key={repo.id}
          sx={{
            borderTop: index === 0 ? "none" : "1px solid",
            borderColor: "border.muted",
          }}
        >
          <RepositoryCard repo={repo} />
        </Box>
      ))}
    </Box>

    {onCreateHref && (
      <Box
        sx={{
          px: 3,
          py: 2,
          borderTop: "1px solid",
          borderColor: "border.muted",
        }}
      >
        <Link
          as={RouterLink}
          to={onCreateHref}
          sx={{ fontSize: 0, display: "inline-flex", alignItems: "center", gap: 1 }}
        >
          <Octicon icon={PlusIcon} size={12} />
          <Text>New repository</Text>
        </Link>
      </Box>
    )}
  </Box>
);

export default RepositoryList;
