package com.gitforge.security;

import com.gitforge.user.User;

/**
 * Principal placed on the security context for an authenticated request.
 *
 * <p>Controllers receive it via {@code @AuthenticationPrincipal}. It carries the
 * fully loaded {@link User} so service-layer ownership checks do not each have
 * to re-read the account.
 */
public record AuthenticatedUser(User user) {

    public java.util.UUID id() {
        return user.getId();
    }

    public String username() {
        return user.getUsername();
    }
}
