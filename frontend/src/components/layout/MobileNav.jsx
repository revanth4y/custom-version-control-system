import { useLocation } from "react-router-dom";
import RouterLink from "../common/RouterLink";
import { ActionList, ActionMenu, IconButton } from "@primer/react";
import { PlusIcon, ThreeBarsIcon } from "@primer/octicons-react";

import { activeNavKey, navItemsFor } from "../../utils/navigation";

/**
 * The same destinations as the wide navigation, folded into a menu.
 *
 * Below the breakpoint the row cannot hold the brand, the navigation, the theme
 * control and the account at once, and the reference has no narrow layout to
 * copy. A menu keeps every destination reachable rather than dropping some of
 * them, and reuses the overlay behaviour the account menu already has: focus
 * trapping, Escape to close, arrow keys between items.
 *
 * "New repository" is here too. On a wide viewport it is its own button; at
 * this width that button is hidden, and this is where it goes instead.
 */
const MobileNav = ({ currentUser }) => {
  const { pathname } = useLocation();
  const items = navItemsFor(currentUser);
  const active = activeNavKey(items, pathname);

  if (items.length === 0) return null;

  return (
    <ActionMenu>
      <ActionMenu.Anchor>
        <IconButton
          icon={ThreeBarsIcon}
          aria-label="Open navigation menu"
          variant="invisible"
          size="small"
          sx={{ display: ["inline-flex", "inline-flex", "none"], color: "fg.default" }}
        />
      </ActionMenu.Anchor>

      <ActionMenu.Overlay align="start">
        <ActionList>
          {items.map(({ key, label, to }) => (
            <ActionList.LinkItem key={key} as={RouterLink} to={to} active={active === key}>
              {label}
            </ActionList.LinkItem>
          ))}

          <ActionList.Divider />

          <ActionList.LinkItem as={RouterLink} to="/new">
            <ActionList.LeadingVisual>
              <PlusIcon />
            </ActionList.LeadingVisual>
            New repository
          </ActionList.LinkItem>
        </ActionList>
      </ActionMenu.Overlay>
    </ActionMenu>
  );
};

export default MobileNav;
