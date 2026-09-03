package com.gitforge.cli;

/**
 * A failure with a code the caller can act on.
 *
 * <p>Every deliberate refusal in the CLI throws one of these rather than
 * returning a message, so there is exactly one place — the dispatcher — that
 * decides how a failure is rendered and what the process exits with. A command
 * that printed an error itself would be a second such place, and the two would
 * eventually disagree about the exit status.
 *
 * <p>The {@code code} is a stable machine-readable token, matching the server's
 * error vocabulary where the failure has a server equivalent. The message is for
 * a person and may change.
 */
public class CliException extends RuntimeException {

    private final String code;
    private final ExitCode exitCode;

    public CliException(String code, ExitCode exitCode, String message) {
        super(message);
        this.code = code;
        this.exitCode = exitCode;
    }

    public CliException(String code, ExitCode exitCode, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.exitCode = exitCode;
    }

    public String code() {
        return code;
    }

    public ExitCode exitCode() {
        return exitCode;
    }

    // ---------------------------------------------------------------- shapes

    public static CliException usage(String message) {
        return new CliException("VALIDATION_FAILED", ExitCode.USAGE, message);
    }

    public static CliException notFound(String message) {
        return new CliException("NOT_FOUND", ExitCode.NOT_FOUND, message);
    }

    public static CliException forbidden(String message) {
        return new CliException("FORBIDDEN", ExitCode.FORBIDDEN, message);
    }

    public static CliException refused(String code, String message) {
        return new CliException(code, ExitCode.REFUSED, message);
    }

    public static CliException sandbox(String message) {
        return new CliException("SANDBOX_VIOLATION", ExitCode.SANDBOX_VIOLATION, message);
    }

    public static CliException conflict(String message) {
        return new CliException("CONFLICT", ExitCode.CONFLICT, message);
    }

    public static CliException remote(String message) {
        return new CliException("REMOTE_TRANSFER_FAILED", ExitCode.REMOTE_TRANSFER, message);
    }

    public static CliException verification(String message) {
        return new CliException("VERIFICATION_FAILED", ExitCode.VERIFICATION_FAILED, message);
    }

    public static CliException failure(String message) {
        return new CliException("ERROR", ExitCode.FAILURE, message);
    }
}
