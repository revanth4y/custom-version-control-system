package com.gitforge.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Instant;

/**
 * Demo seeding configuration, bound from {@code gitforge.demo.*}.
 *
 * @param reset when true, wipe everything and rebuild the demo dataset at
 *     startup. Destructive, and only honoured under the {@code demo} profile.
 * @param password the password every seeded account is given. Known and
 *     published on purpose: these accounts exist to be signed into while
 *     showing the application, and the profile that creates them cannot run
 *     against a real deployment.
 * @param epoch the instant the dataset is dated from. Left unset it is "now",
 *     so the contribution calendar shows recent activity; pinned to a fixed
 *     instant the dataset is byte-identical every time, object ids included.
 */
@ConfigurationProperties(prefix = "gitforge.demo")
public record DemoProperties(boolean reset, String password, Instant epoch) {

    public Instant epochOrNow() {
        return epoch == null ? Instant.now() : epoch;
    }
}
