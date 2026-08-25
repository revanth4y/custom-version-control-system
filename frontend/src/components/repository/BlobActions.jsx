import { useEffect, useState } from "react";
import { Box, Button, Octicon, Text } from "@primer/react";
import { CheckIcon, CopyIcon } from "@primer/octicons-react";

/**
 * The controls in a file's header: how to read it, and how to take it away.
 *
 * "Raw" is a toggle rather than a link. There is no endpoint that serves a
 * file's bytes — `/blob` answers with JSON — so a link labelled Raw would
 * either lie about where it goes or navigate away from the page to show the
 * same text. Toggling in place is the honest version of the control, and it is
 * the one that helps: what a reader wants from Raw is the source of a rendered
 * document, which is exactly what this gives them.
 *
 * It is offered only where the two views differ. For a file already shown as
 * source there is nothing to toggle to, and for a binary file there is no text
 * to reveal — inventing one would mean printing bytes as characters, which is
 * the thing the binary state exists to prevent.
 */
const BlobActions = ({ canToggleRaw, raw, onToggleRaw, copyText }) => {
  const [copied, setCopied] = useState(false);

  /* The confirmation is temporary, and the timer has to be cleared: navigating
     away mid-countdown would otherwise set state on an unmounted component. */
  useEffect(() => {
    if (!copied) return undefined;
    const timer = setTimeout(() => setCopied(false), 2000);
    return () => clearTimeout(timer);
  }, [copied]);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(copyText ?? "");
      setCopied(true);
    } catch {
      /* Denied permission, or an insecure origin. Saying nothing is better than
         claiming a copy that did not happen. */
      setCopied(false);
    }
  };

  return (
    <Box sx={{ display: "flex", alignItems: "center", gap: 2, flexShrink: 0 }}>
      {canToggleRaw && (
        <Button
          size="small"
          variant="invisible"
          aria-pressed={raw}
          onClick={onToggleRaw}
          sx={{ color: raw ? "fg.default" : "fg.muted" }}
        >
          Raw
        </Button>
      )}

      {copyText != null && (
        <Button
          size="small"
          variant="invisible"
          onClick={copy}
          aria-label={copied ? "File contents copied" : "Copy file contents"}
          sx={{ color: copied ? "success.fg" : "fg.muted" }}
        >
          <Box sx={{ display: "inline-flex", alignItems: "center", gap: 1 }}>
            <Octicon icon={copied ? CheckIcon : CopyIcon} size={14} />
            <Text sx={{ fontSize: 0, display: ["none", "inline"] }}>
              {copied ? "Copied" : "Copy"}
            </Text>
          </Box>
        </Button>
      )}
    </Box>
  );
};

export default BlobActions;
