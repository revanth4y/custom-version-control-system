import { forwardRef } from "react";
import { Link } from "react-router-dom";

/**
 * A router link that does not carry Primer's `sx` onto the page.
 *
 * `<Link as={RouterLink} sx={{...}}>` and its Box, Button and IconButton
 * siblings render the styling correctly, but also emit a literal
 * `sx="[object Object]"` attribute on the anchor. styled-components filters
 * unknown props only when it controls a DOM element; given a component in `as`
 * it forwards everything, and react-router spreads what it does not recognise
 * straight onto the `<a>`. Nothing warns, because a lowercase attribute is
 * legal as far as React is concerned — it is simply invalid HTML that shipped
 * on roughly fifteen elements per page.
 *
 * Swallowing `sx` here fixes every one of those sites at once. The class name
 * Primer generates still arrives, so the styling is untouched; only the
 * redundant attribute stops being written. `forwardRef` matters: overlays and
 * menus anchor themselves to these elements.
 */
const RouterLink = forwardRef(({ sx, ...rest }, ref) => <Link ref={ref} {...rest} />);

RouterLink.displayName = "RouterLink";

export default RouterLink;
