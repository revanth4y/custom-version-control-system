import { ActionList, ActionMenu, Box, Octicon } from "@primer/react";
import { KebabHorizontalIcon, PencilIcon, TrashIcon } from "@primer/octicons-react";

/**
 * Edit and delete for a comment.
 *
 * Behind a menu rather than always visible: a thread is for reading, and two
 * buttons on every comment would compete with the text. Shown only to the
 * author or the repository owner - though that is presentation, and the server
 * refuses either way.
 */
const CommentActions = ({ onEdit, onDelete }) => (
  <ActionMenu>
    <ActionMenu.Anchor>
      <Box
        as="button"
        type="button"
        aria-label="Comment actions"
        sx={{
          display: "flex",
          alignItems: "center",
          bg: "transparent",
          border: "none",
          borderRadius: 2,
          px: 2,
          py: 1,
          cursor: "pointer",
          color: "fg.muted",
          "&:hover": { color: "fg.default", bg: "canvas.inset" },
        }}
      >
        <Octicon icon={KebabHorizontalIcon} size={16} />
      </Box>
    </ActionMenu.Anchor>

    <ActionMenu.Overlay align="end">
      <ActionList>
        <ActionList.Item onSelect={onEdit}>
          <ActionList.LeadingVisual>
            <PencilIcon />
          </ActionList.LeadingVisual>
          Edit
        </ActionList.Item>
        <ActionList.Item variant="danger" onSelect={onDelete}>
          <ActionList.LeadingVisual>
            <TrashIcon />
          </ActionList.LeadingVisual>
          Delete
        </ActionList.Item>
      </ActionList>
    </ActionMenu.Overlay>
  </ActionMenu>
);

export default CommentActions;
