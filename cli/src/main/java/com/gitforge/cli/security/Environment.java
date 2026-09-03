package com.gitforge.cli.security;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What a child process would be allowed to see.
 *
 * <p>An allowlist rather than a blocklist. A blocklist has to anticipate every
 * variable worth hiding, and the environment of a developer machine or a CI
 * runner is full of credentials nobody put there on purpose —
 * {@code AWS_SECRET_ACCESS_KEY}, {@code GITHUB_TOKEN}, whatever the last tool
 * exported. Naming what may pass is the only version of this that stays correct
 * as the environment grows.
 *
 * <p>The CLI does not currently start child processes: every operation is served
 * by the engine in-process or by an HTTP call, and there is no shell anywhere in
 * it. This class exists so that the rule is decided and tested now, before some
 * later command needs a subprocess and the question is answered in a hurry.
 */
public final class Environment {

    /** Exact names that may pass. */
    private static final Set<String> ALLOWED = Set.of("HOME", "PATH", "USERPROFILE", "TMPDIR", "TEMP");

    /** Prefixes that may pass: the tool's own configuration. */
    private static final String OWN_PREFIX = "GITFORGE_";

    private Environment() {
    }

    /**
     * The subset of an environment a child may inherit.
     *
     * <p>Sorted, so a preview or an audit line describing it is deterministic.
     */
    public static Map<String, String> filter(Map<String, String> source) {
        Map<String, String> allowed = new TreeMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (permits(entry.getKey())) {
                allowed.put(entry.getKey(), entry.getValue());
            }
        }
        return new LinkedHashMap<>(allowed);
    }

    /** Whether one variable would be passed on. */
    public static boolean permits(String name) {
        return name != null && (ALLOWED.contains(name) || name.startsWith(OWN_PREFIX));
    }
}
