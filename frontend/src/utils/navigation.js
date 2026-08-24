/**
 * What the global navigation offers, and which entry is currently active.
 *
 * Kept as plain functions rather than folded into the component so the rules
 * can be tested directly. Deciding what is active from a pathname is fiddly —
 * every route is a prefix of some other route — and that is exactly the kind of
 * logic that should not need a browser to verify.
 */

/**
 * The destinations a viewer actually has.
 *
 * Signed out there are none: both entries need an identity, and offering a
 * control that bounces to the sign-in page is worse than not offering it.
 *
 * The reference header also carries Explore and Docs. Neither exists — there is
 * no discovery endpoint and no documentation site — and a nav item that leads
 * nowhere is a broken control, so they are left out until something backs them.
 */
export const navItemsFor = (currentUser) => {
  if (!currentUser?.username) return [];

  return [
    { key: "dashboard", label: "Dashboard", to: "/" },
    { key: "repositories", label: "Repositories", to: `/${currentUser.username}` },
  ];
};

/**
 * Which entry the current path belongs to.
 *
 * The dashboard is matched exactly: "/" is a prefix of every route, so a prefix
 * test would light it up on every page in the product.
 *
 * Everything else matches on a path segment boundary, so `/revant` is active
 * for `/revant/repo` but `/revanthy` — a different person — is not.
 */
export const activeNavKey = (items, pathname) => {
  const path = pathname.replace(/\/+$/, "") || "/";

  const match = items.find(({ to }) => {
    if (to === "/") return path === "/";
    return path === to || path.startsWith(`${to}/`);
  });

  return match?.key ?? null;
};
