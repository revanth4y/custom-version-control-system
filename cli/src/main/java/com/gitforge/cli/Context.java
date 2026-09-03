package com.gitforge.cli;

import com.gitforge.cli.config.CliConfig;
import com.gitforge.cli.config.Credentials;
import com.gitforge.cli.options.GlobalOptions;
import com.gitforge.cli.output.Output;
import com.gitforge.cli.security.AuditLog;
import com.gitforge.cli.security.Redactor;
import com.gitforge.cli.security.SandboxPath;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Everything a command is allowed to reach.
 *
 * <p>Commands take one of these and nothing else: no static state, no ambient
 * {@code System.out}, no direct filesystem root. That is what makes them
 * testable — a test constructs a context over a temporary directory and a
 * capturing stream, and the command cannot tell the difference — and it is also
 * what makes the sandbox enforceable, because there is no second way to get a
 * path.
 *
 * <p>The two refusals that must happen before a command runs live here rather
 * than in the commands: {@link #requireMutable} for read-only mode, and
 * {@link #confirm} for destructive acts. A command that forgot to call one would
 * be a hole in the boundary, so the dispatcher calls {@code requireMutable} for
 * anything declared as mutating, and destructive commands are the only ones that
 * need to remember {@code confirm}.
 */
public final class Context {

    private final GlobalOptions options;
    private final Output output;
    private final SandboxPath sandbox;
    private final CliConfig config;
    private final Credentials credentials;
    private final AuditLog audit;
    private final Redactor redactor;
    private final Map<String, String> environment;
    private final List<String> warnings = new ArrayList<>();
    private final List<String> refsMoved = new ArrayList<>();
    private String authorizationDecision = "n/a";

    public Context(
            GlobalOptions options,
            Output output,
            SandboxPath sandbox,
            CliConfig config,
            Credentials credentials,
            AuditLog audit,
            Redactor redactor,
            Map<String, String> environment) {
        this.options = options;
        this.output = output;
        this.sandbox = sandbox;
        this.config = config;
        this.credentials = credentials;
        this.audit = audit;
        this.redactor = redactor;
        this.environment = environment;
    }

    public GlobalOptions options() {
        return options;
    }

    public Output out() {
        return output;
    }

    public SandboxPath sandbox() {
        return sandbox;
    }

    public CliConfig config() {
        return config;
    }

    public Credentials credentials() {
        return credentials;
    }

    public AuditLog audit() {
        return audit;
    }

    public Redactor redactor() {
        return redactor;
    }

    public Map<String, String> environment() {
        return environment;
    }

    /** A path inside the sandbox, or a refusal. The only way to obtain one. */
    public Path path(String candidate) {
        return sandbox.resolve(candidate);
    }

    /**
     * Refuses if the CLI is running read-only.
     *
     * <p>Called by the dispatcher before a mutating command starts, so the
     * refusal happens before any validation, any network call and any file is
     * opened. Checking later would mean read-only mode still did work on the
     * caller's behalf before declining to finish it.
     */
    public void requireMutable(String what) {
        if (options.readOnly()) {
            throw CliException.refused(
                    "READ_ONLY",
                    "Refusing to " + what + ": the CLI is running read-only");
        }
    }

    /**
     * Asks before something destructive, and fails closed when it cannot ask.
     *
     * <p>{@code --yes} is consent given in advance. Without it, a
     * non-interactive run is refused rather than assumed: a script that did not
     * say yes did not mean yes, and the alternative — treating silence as
     * agreement — is how an unattended job deletes a branch nobody meant to
     * delete.
     */
    public void confirm(String what) {
        if (options.assumeYes()) {
            return;
        }
        if (options.noInput() || System.console() == null) {
            throw CliException.refused(
                    "CONFIRMATION_REQUIRED",
                    what + " needs confirmation. Pass --yes to consent in advance.");
        }
        output.line(what + " [y/N]: ");
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String answer = reader.readLine();
            if (answer == null || !(answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes"))) {
                throw CliException.refused("CONFIRMATION_DECLINED", "Cancelled.");
            }
        } catch (java.io.IOException unreadable) {
            throw CliException.refused(
                    "CONFIRMATION_REQUIRED", "Could not read a confirmation. Pass --yes.");
        }
    }

    /** A non-fatal note, carried into the envelope's {@code warnings}. */
    public void warn(String warning) {
        warnings.add(warning);
    }

    public List<String> warnings() {
        return warnings;
    }

    /** Records a reference this command moved, for the audit log and previews. */
    public void movedRef(String ref) {
        refsMoved.add(ref);
    }

    public List<String> refsMoved() {
        return refsMoved;
    }

    public void decided(String decision) {
        this.authorizationDecision = decision;
    }

    public String authorizationDecision() {
        return authorizationDecision;
    }
}
