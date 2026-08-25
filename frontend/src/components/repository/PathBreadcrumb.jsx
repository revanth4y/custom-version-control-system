import RouterLink from "../common/RouterLink";
import { Box, Link, Text } from "@primer/react";

/**
 * The trail of directories leading to the current location.
 *
 * Every segment except the last is a link, so moving back up is one click at any
 * depth rather than repeated use of the browser's back button.
 */
const PathBreadcrumb = ({ owner, name, refName, path }) => {
  const segments = (path ?? "").split("/").filter(Boolean);

  const linkTo = (index) => {
    const target = segments.slice(0, index + 1).join("/");
    return `/${owner}/${name}/tree/${encodeURIComponent(refName)}/${target}`;
  };

  return (
    <Box
      as="nav"
      aria-label="Path"
      sx={{
        display: "flex",
        flexWrap: "wrap",
        alignItems: "center",
        gap: 1,
        fontSize: 2,
        minWidth: 0,
      }}
    >
      {segments.length === 0 ? (
        <Text sx={{ fontWeight: 600 }}>{name}</Text>
      ) : (
        <Link as={RouterLink} to={`/${owner}/${name}/tree/${encodeURIComponent(refName)}`}>
          {name}
        </Link>
      )}

      {segments.map((segment, index) => {
        const isLast = index === segments.length - 1;
        return (
          <Box key={`${segment}-${index}`} sx={{ display: "flex", alignItems: "center", gap: 1, minWidth: 0 }}>
            <Text sx={{ color: "fg.muted" }}>/</Text>
            {isLast ? (
              <Text sx={{ fontWeight: 600, wordBreak: "break-all" }}>{segment}</Text>
            ) : (
              <Link as={RouterLink} to={linkTo(index)} sx={{ wordBreak: "break-all" }}>
                {segment}
              </Link>
            )}
          </Box>
        );
      })}
    </Box>
  );
};

export default PathBreadcrumb;
