import { Link as RouterLink } from "react-router-dom";
import { Box, Text, ActionMenu, ActionList, Button } from "@primer/react";
import { PlusIcon, SignOutIcon } from "@primer/octicons-react";

import { useAuth } from "../../hooks/useAuth";
import GitForgeMark from "../common/GitForgeMark";
import IdentityAvatar from "../common/IdentityAvatar";

/**
 * The bar carried by every signed-in page.
 *
 * Kept deliberately sparse: it is present on every screen, so anything added
 * here competes with the page's own content for attention.
 */
const AppHeader = () => {
  const { currentUser, logout } = useAuth();

  return (
    <Box
      as="header"
      sx={{
        display: "flex",
        alignItems: "center",
        justifyContent: "space-between",
        gap: 3,
        px: [3, 3, 4],
        height: "56px",
        bg: "canvas.subtle",
        borderBottom: "1px solid",
        borderColor: "border.default",
        position: "sticky",
        top: 0,
        zIndex: 10,
      }}
    >
      <Box
        as={RouterLink}
        to="/"
        sx={{
          display: "flex",
          alignItems: "center",
          gap: 2,
          color: "fg.default",
          textDecoration: "none",
          "&:hover": { color: "accent.fg" },
        }}
      >
        <Box sx={{ display: "flex", color: "accent.fg" }}>
          <GitForgeMark size={22} />
        </Box>
        <Text sx={{ fontWeight: 600, fontSize: 2, letterSpacing: "-0.01em" }}>GitForge</Text>
      </Box>

      {currentUser && (
        <Box sx={{ display: "flex", alignItems: "center", gap: 2 }}>
          <Button
            as={RouterLink}
            to="/new"
            size="small"
            leadingVisual={PlusIcon}
            sx={{ display: ["none", "inline-flex"] }}
          >
            New repository
          </Button>

          <ActionMenu>
            <ActionMenu.Anchor>
              <Box
                as="button"
                type="button"
                aria-label="Open account menu"
                sx={{
                  display: "flex",
                  alignItems: "center",
                  gap: 2,
                  bg: "transparent",
                  border: "1px solid",
                  borderColor: "border.default",
                  borderRadius: 2,
                  px: 2,
                  py: 1,
                  cursor: "pointer",
                  color: "fg.muted",
                  "&:hover": { borderColor: "accent.emphasis", color: "fg.default" },
                }}
              >
                <IdentityAvatar username={currentUser.username} size={20} />
                <Text sx={{ fontSize: 1, display: ["none", "inline"] }}>{currentUser.username}</Text>
              </Box>
            </ActionMenu.Anchor>

            <ActionMenu.Overlay align="end">
              <ActionList>
                <ActionList.LinkItem as={RouterLink} to="/new">
                  <ActionList.LeadingVisual>
                    <PlusIcon />
                  </ActionList.LeadingVisual>
                  New repository
                </ActionList.LinkItem>
                <ActionList.Divider />
                <ActionList.Item variant="danger" onSelect={logout}>
                  <ActionList.LeadingVisual>
                    <SignOutIcon />
                  </ActionList.LeadingVisual>
                  Sign out
                </ActionList.Item>
              </ActionList>
            </ActionMenu.Overlay>
          </ActionMenu>
        </Box>
      )}
    </Box>
  );
};

export default AppHeader;
