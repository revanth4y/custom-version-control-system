import { useId } from "react";
import { Box, Dialog, Heading, Text } from "@primer/react";

/**
 * A modal with a heading, a scrollable body and a row of actions.
 *
 * Primer's stable Dialog is the older one: it takes `isOpen` and children and
 * leaves the internal structure to the caller. (The newer API with `title` and
 * `footerButtons` lives in the drafts entry point, which this project does not
 * use.) Rather than repeat that structure in every dialog, it is arranged once
 * here so they cannot drift apart.
 *
 * The height is capped against the viewport with the body scrolling inside, so
 * a dialog on a short window keeps its actions reachable instead of running off
 * the bottom of the screen.
 */
const ModalDialog = ({
  title,
  description,
  onClose,
  actions,
  children,
  initialFocusRef,
  role = "dialog",
}) => {
  const titleId = useId();
  const descriptionId = useId();

  return (
    <Dialog
      isOpen
      onDismiss={onClose}
      initialFocusRef={initialFocusRef}
      role={role}
      aria-labelledby={titleId}
      aria-describedby={description ? descriptionId : undefined}
      sx={{
        display: "flex",
        flexDirection: "column",
        // Below 750px Primer already makes this a full-bleed sheet with
        // height: 100dvh, which is the right shape for a phone. Setting a width
        // or a max height there fights it and leaves the panel inset with dead
        // space, so both are only applied from the medium breakpoint up.
        width: [null, null, "540px"],
        maxHeight: [null, null, "calc(100vh - 48px)"],
      }}
    >
      <Box
        sx={{
          // Right padding clears the close button Primer renders at the corner.
          p: 3,
          pr: 6,
          borderBottom: "1px solid",
          borderColor: "border.muted",
          flexShrink: 0,
        }}
      >
        <Heading as="h2" id={titleId} sx={{ fontSize: 2, fontWeight: 600 }}>
          {title}
        </Heading>
        {description && (
          <Text id={descriptionId} sx={{ fontSize: 0, color: "fg.muted", display: "block", mt: 1 }}>
            {description}
          </Text>
        )}
      </Box>

      <Box sx={{ p: 3, overflowY: "auto", flex: 1, minHeight: 0 }}>{children}</Box>

      <Box
        sx={{
          p: 3,
          borderTop: "1px solid",
          borderColor: "border.muted",
          display: "flex",
          justifyContent: "flex-end",
          gap: 2,
          flexWrap: "wrap",
          flexShrink: 0,
        }}
      >
        {actions}
      </Box>
    </Dialog>
  );
};

export default ModalDialog;
