package com.gitforge.vcsapi;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where repository storage lives, bound from {@code gitforge.storage.*}.
 *
 * <p>Configured rather than hardcoded: the path differs between a developer's
 * machine, a container, and a mounted volume in production, and it is the one
 * piece of state the application cannot recreate if it is lost.
 *
 * @param root directory holding one subdirectory per repository
 */
@ConfigurationProperties(prefix = "gitforge.storage")
public record VcsStorageProperties(String root) {
}
