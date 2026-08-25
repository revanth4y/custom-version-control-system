import { forwardRef } from "react";
import { Octicon as PrimerOcticon } from "@primer/react";

/**
 * Primer's Octicon, without `sx` landing on the `<svg>`.
 *
 * The same leak as [RouterLink]: Primer turns `sx` into a class, then hands the
 * remaining props to the component underneath, which spreads them onto its
 * element. For an icon that element is an `<svg>`, so every icon on the page
 * carried a redundant `sx="[object Object]"`.
 *
 * The strip has to happen below Primer, not above it — taking `sx` off before
 * Primer sees it would remove the styling along with the attribute. So the icon
 * component itself is wrapped, and Primer goes on receiving `sx` exactly as
 * before.
 *
 * Wrappers are cached per icon. Building one during render would hand React a
 * new component type on every pass, which unmounts and remounts the icon
 * instead of updating it.
 */
const wrappers = new WeakMap();

const withoutSx = (Component) => {
  if (!Component) return Component;

  const cached = wrappers.get(Component);
  if (cached) return cached;

  const Wrapped = forwardRef(({ sx, ...rest }, ref) => <Component ref={ref} {...rest} />);
  Wrapped.displayName = `withoutSx(${Component.displayName || Component.name || "Icon"})`;
  wrappers.set(Component, Wrapped);

  return Wrapped;
};

const Octicon = forwardRef(({ icon, ...rest }, ref) => (
  <PrimerOcticon ref={ref} icon={withoutSx(icon)} {...rest} />
));

Octicon.displayName = "Octicon";

export default Octicon;
