import { Avatar } from "@primer/react";

import { avatarFor } from "../../utils/avatar";

/**
 * A generated avatar for a username.
 *
 * Uploaded avatars are not part of the product yet, and there is no acceptable
 * placeholder to fall back on: a remote image would make every page depend on a
 * third party, and borrowing another service's default would put someone else's
 * artwork in our interface. So the image is derived from the name itself —
 * deterministic, offline, and distinct per person.
 *
 * The hue comes from the characters of the username, so the same person is
 * always the same colour, and two people are unlikely to collide.
 */
const IdentityAvatar = ({ username, size = 20, ...rest }) => (
  <Avatar src={avatarFor(username)} size={size} square alt="" {...rest} />
);

export default IdentityAvatar;
