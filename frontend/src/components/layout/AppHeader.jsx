import RouterLink from "../common/RouterLink";
import { Box, Text, ActionMenu, ActionList, Button, IconButton } from "@primer/react";
import { PersonIcon, PlusIcon, SignOutIcon } from "@primer/octicons-react";

import { useAuth } from "../../hooks/useAuth";
import ThemeToggle from "../common/ThemeToggle";
import IdentityAvatar from "../common/IdentityAvatar";
import BrandLockup from "./BrandLockup";
import GlobalNav from "./GlobalNav";
import HeaderBar from "./HeaderBar";
import MobileNav from "./MobileNav";

/**
 * The bar carried by every page.
 *
 * Laid out as the reference has it: the brand at the left edge, the navigation
 * and the controls gathered at the right, and the space between them left open.
 *
 * Two things the reference shows are absent, and deliberately so. The search
 * field has nothing behind it — there is no search endpoint — and a box that
 * accepts typing and can never answer is worse than no box. The notification
 * bell has no event source, and a bell is a claim that something happened. Both
 * are omitted rather than mocked; the rest of the row keeps its positions.
 */
const AppHeader = () => {
  const { currentUser, logout } = useAuth();

  return (
    <HeaderBar>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
        {currentUser && <MobileNav currentUser={currentUser} />}
        <BrandLockup />
      </Box>

      {/* Holds the gap the search field occupies in the reference, so the
          navigation and the controls stay at the right edge rather than sliding
          across to meet the brand. */}
      <Box sx={{ flex: 1, minWidth: 0 }} />

      <GlobalNav currentUser={currentUser} />

      {/* Signed out, the header offers the way in rather than nothing: a
          public repository is readable without an account, so an anonymous
          visitor is an ordinary visitor, not a lost one. */}
      {!currentUser && (
        <Box sx={{ display: "flex", alignItems: "center", gap: 2, flexShrink: 0 }}>
          <ThemeToggle />
          <Button as={RouterLink} to="/login" size="small">
            Sign in
          </Button>
          <Button
            as={RouterLink}
            to="/signup"
            size="small"
            variant="primary"
            sx={{ display: ["none", "inline-flex"] }}
          >
            Create an account
          </Button>
        </Box>
      )}

      {currentUser && (
        <Box sx={{ display: "flex", alignItems: "center", gap: 2, flexShrink: 0 }}>
          <IconButton
            as={RouterLink}
            to="/new"
            icon={PlusIcon}
            aria-label="New repository"
            variant="invisible"
            size="small"
            sx={{ display: ["none", "none", "inline-flex"], color: "fg.muted" }}
          />

          <ThemeToggle />

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
                <ActionList.LinkItem as={RouterLink} to={`/${currentUser.username}`}>
                  <ActionList.LeadingVisual>
                    <PersonIcon />
                  </ActionList.LeadingVisual>
                  Your profile
                </ActionList.LinkItem>
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
    </HeaderBar>
  );
};

export default AppHeader;
