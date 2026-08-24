import { useEffect, useState } from "react";
import { Box } from "@primer/react";

import engine, { highlightLine } from "../../utils/highlighter";
import { languageFor } from "../../utils/language";
import "../../theme/highlight.css";

/**
 * A file's source, one table row per line.
 *
 * A row per line is what keeps the numbering honest: the gutter cannot drift
 * out of step with the code because they are the same row. The gutter is not
 * selectable, so copying a snippet takes the code and leaves the numbers.
 *
 * Highlighting is applied after the fact. The source renders immediately as
 * plain text and gains colour when the grammars arrive, so a slow chunk delays
 * the paint of nothing. If they never arrive the file is still perfectly
 * readable, which is the right failure for a convenience.
 */
const SourceView = ({ path, lines, numbered, binary }) => {
  const language = languageFor(path, { binary });
  const [hljs, setHljs] = useState(null);

  useEffect(() => {
    if (!language) return undefined;

    let live = true;
    engine().then((loaded) => {
      if (live) setHljs(loaded);
    });

    return () => {
      live = false;
    };
  }, [language]);

  return (
    <Box sx={{ overflowX: "auto", bg: "canvas.subtle" }}>
      <Box
        as="table"
        aria-label="File contents"
        sx={{ borderCollapse: "collapse", width: "100%", fontFamily: "mono", fontSize: 0 }}
      >
        <Box as="tbody">
          {lines.map((line, index) => {
            const html = highlightLine(hljs, line, language);

            /* The pane sits on the panel's own surface so that syntax colours
               keep their contrast — on the inset grey the keyword and comment
               tokens measured 4.39:1, just under AA. Hover is the inset
               instead, which is the same pair of colours the other way round. */
            return (
              <Box as="tr" key={index} sx={{ "&:hover": { bg: "canvas.inset" } }}>
                {numbered && (
                  <Box
                    as="td"
                    /* Announced as a row header rather than a cell, so a screen
                       reader says which line it is reading. */
                    scope="row"
                    sx={{
                      // The gutter must not be selectable, or copying a snippet
                      // takes the line numbers with it.
                      userSelect: "none",
                      textAlign: "right",
                      color: "fg.subtle",
                      px: 3,
                      width: "1%",
                      whiteSpace: "nowrap",
                      verticalAlign: "top",
                      borderRight: "1px solid",
                      borderColor: "border.muted",
                    }}
                  >
                    {index + 1}
                  </Box>
                )}

                <Box
                  as="td"
                  sx={{ px: 3, whiteSpace: "pre", color: "fg.default", verticalAlign: "top" }}
                  /* highlight.js escapes the text it wraps, and the input is a
                     single line of the file being viewed. An empty line renders
                     a space so the row keeps its height. */
                  {...(html
                    ? { dangerouslySetInnerHTML: { __html: html } }
                    : { children: line === "" ? " " : line })}
                />
              </Box>
            );
          })}
        </Box>
      </Box>
    </Box>
  );
};

export default SourceView;
