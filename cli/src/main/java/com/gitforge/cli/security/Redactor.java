package com.gitforge.cli.security;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The last thing every stream passes through.
 *
 * <p>A token leaks the same way whether it is printed deliberately, echoed back
 * inside a server error, or written to the audit log by a command that never
 * knew it was holding one. Trying to remember not to print it at each of those
 * points is how it eventually gets printed, so the CLI does the opposite:
 * everything on its way out — stdout, stderr, verbose tracing and the audit log
 * — goes through here, and there is no path that bypasses it.
 *
 * <p>Two mechanisms, because either alone leaves a gap.
 *
 * <p><strong>Known secrets</strong> are registered when they are loaded, and
 * matched literally. This catches a token however it is embedded, including
 * inside a longer string the CLI never parsed.
 *
 * <p><strong>Shapes</strong> catch what was never registered: a JWT in a server
 * response, a bearer header quoted back in an error, credentials in a URL. This
 * is a heuristic and is meant to be — an over-redacted diagnostic is a nuisance,
 * a leaked credential is an incident, and the trade is not close.
 */
public final class Redactor {

    /** What replaces anything matched. Fixed, so output stays deterministic. */
    public static final String MASK = "[REDACTED]";

    /** Three dot-separated base64url runs: a JWT, whatever it is called in context. */
    private static final Pattern JWT =
            Pattern.compile("eyJ[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}");

    /** An Authorization header, however it was spelled. */
    private static final Pattern BEARER =
            Pattern.compile("(?i)(bearer|token)\\s+[A-Za-z0-9._~+/=-]{8,}");

    /** Credentials inside a URL, which the remote rules already refuse but errors may still quote. */
    private static final Pattern URL_CREDENTIALS =
            Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)[^/@\\s:]+:[^/@\\s]+@");

    /** A key=value pair whose key sounds like a secret. */
    private static final Pattern SECRET_ASSIGNMENT =
            Pattern.compile("(?i)\\b(password|passwd|secret|token|api[_-]?key|authorization)\\b"
                    + "(\\s*[:=]\\s*)(\"?)([^\\s\"',;]{1,4096})(\"?)");

    private final List<String> known = new ArrayList<>();

    /**
     * Registers a literal secret to mask wherever it appears.
     *
     * <p>Short values are ignored: masking every occurrence of a three-character
     * string would redact ordinary output and teach the reader to distrust the
     * mask, which is worse than not having one.
     */
    public synchronized void remember(String secret) {
        if (secret != null && secret.length() >= 8 && !known.contains(secret)) {
            known.add(secret);
        }
    }

    /** Forgets every registered secret. Used when credentials are cleared. */
    public synchronized void forgetAll() {
        known.clear();
    }

    /** The text, with everything that looks like a credential removed. */
    public synchronized String scrub(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (String secret : known) {
            out = out.replace(secret, MASK);
        }
        out = JWT.matcher(out).replaceAll(MASK);
        out = BEARER.matcher(out).replaceAll("$1 " + MASK);
        out = URL_CREDENTIALS.matcher(out).replaceAll("$1" + MASK + "@");
        out = SECRET_ASSIGNMENT.matcher(out)
                .replaceAll(mr -> Matcher.quoteReplacement(
                        mr.group(1) + mr.group(2) + mr.group(3) + MASK + mr.group(5)));
        return out;
    }
}
