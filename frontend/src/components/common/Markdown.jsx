import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Box, Heading, Text, Link } from "@primer/react";

/**
 * Renders Markdown from a repository.
 *
 * The content is written by users, so safety is the first concern. react-markdown
 * builds React elements rather than setting innerHTML, and raw HTML in the
 * source is ignored unless `rehype-raw` is added — which it deliberately is not.
 * That closes the obvious injection route without needing a sanitiser to catch
 * it afterwards.
 *
 * Links are additionally forced to open in a new tab with `noopener`, so a
 * README cannot navigate the application away or reach back through
 * `window.opener`.
 */
const Markdown = ({ children }) => (
  <Box
    sx={{
      fontSize: 1,
      lineHeight: 1.6,
      color: "fg.default",
      // Prose is capped so long lines stay readable on a wide display.
      maxWidth: "72ch",
      "& > *:first-child": { mt: 0 },
      "& > *:last-child": { mb: 0 },
    }}
  >
    <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>
      {children}
    </ReactMarkdown>
  </Box>
);

/**
 * A heading inside user-written content.
 *
 * Rendered one level below what the Markdown says: a README beginning with `#`
 * would otherwise emit a second `<h1>` on a page whose own title is already
 * the first, leaving the document with two competing top-level headings. The
 * visual size is kept, so only the outline changes.
 */
const heading = (level, fontSize, rule) => {
  const Component = ({ children }) => (
    <Heading
      as={`h${Math.min(level + 1, 6)}`}
      sx={{
        fontSize,
        fontWeight: 600,
        mt: level === 1 ? 0 : 4,
        mb: 2,
        pb: rule ? 2 : 0,
        borderBottom: rule ? "1px solid" : "none",
        borderColor: "border.muted",
        lineHeight: 1.3,
      }}
    >
      {children}
    </Heading>
  );
  Component.displayName = `MarkdownHeading${level}`;
  return Component;
};

const components = {
  h1: heading(1, 4, true),
  h2: heading(2, 3, true),
  h3: heading(3, 2, false),
  h4: heading(4, 1, false),
  h5: heading(5, 1, false),
  h6: heading(6, 0, false),

  p: ({ children }) => <Text as="p" sx={{ mt: 0, mb: 3 }}>{children}</Text>,

  a: ({ href, children }) => (
    <Link href={href} target="_blank" rel="noopener noreferrer nofollow">
      {children}
    </Link>
  ),

  // GFM marks task lists with these classes. Such items already carry a
  // checkbox, so a bullet beside it is a second marker for the same thing.
  ul: ({ children, className }) => (
    <Box
      as="ul"
      sx={{
        pl: className?.includes("contains-task-list") ? 0 : 4,
        listStyle: className?.includes("contains-task-list") ? "none" : "disc",
        mt: 0,
        mb: 3,
      }}
    >
      {children}
    </Box>
  ),
  ol: ({ children }) => <Box as="ol" sx={{ pl: 4, mt: 0, mb: 3 }}>{children}</Box>,
  li: ({ children, className }) => (
    <Box
      as="li"
      sx={{
        mb: 1,
        display: className?.includes("task-list-item") ? "flex" : "list-item",
        alignItems: "center",
        gap: 2,
        "& input[type=checkbox]": { accentColor: "#E0763D", margin: 0 },
      }}
    >
      {children}
    </Box>
  ),

  blockquote: ({ children }) => (
    <Box
      as="blockquote"
      sx={{
        borderLeft: "3px solid",
        borderColor: "border.default",
        pl: 3,
        ml: 0,
        mb: 3,
        color: "fg.muted",
      }}
    >
      {children}
    </Box>
  ),

  code: ({ inline, children }) =>
    inline ? (
      <Box
        as="code"
        sx={{
          fontFamily: "mono",
          fontSize: 0,
          bg: "neutral.subtle",
          borderRadius: 1,
          px: 1,
          py: "2px",
        }}
      >
        {children}
      </Box>
    ) : (
      <Box as="code" sx={{ fontFamily: "mono", fontSize: 0 }}>
        {children}
      </Box>
    ),

  pre: ({ children }) => (
    <Box
      as="pre"
      sx={{
        bg: "canvas.inset",
        border: "1px solid",
        borderColor: "border.default",
        borderRadius: 2,
        p: 3,
        mt: 0,
        mb: 3,
        // Code blocks scroll inside themselves rather than widening the page.
        overflowX: "auto",
      }}
    >
      {children}
    </Box>
  ),

  table: ({ children }) => (
    <Box sx={{ overflowX: "auto", mb: 3 }}>
      <Box as="table" sx={{ borderCollapse: "collapse", width: "100%" }}>
        {children}
      </Box>
    </Box>
  ),
  th: ({ children }) => (
    <Box
      as="th"
      sx={{
        border: "1px solid",
        borderColor: "border.default",
        px: 3,
        py: 2,
        textAlign: "left",
        fontWeight: 600,
        bg: "canvas.subtle",
      }}
    >
      {children}
    </Box>
  ),
  td: ({ children }) => (
    <Box as="td" sx={{ border: "1px solid", borderColor: "border.default", px: 3, py: 2 }}>
      {children}
    </Box>
  ),

  hr: () => (
    <Box as="hr" sx={{ border: "none", borderTop: "1px solid", borderColor: "border.default", my: 4 }} />
  ),

  img: ({ src, alt }) => (
    <Box as="img" src={src} alt={alt ?? ""} sx={{ maxWidth: "100%", borderRadius: 2 }} />
  ),
};

export default Markdown;
