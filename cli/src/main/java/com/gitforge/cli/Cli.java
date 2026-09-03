package com.gitforge.cli;

import com.gitforge.cli.command.Command;
import com.gitforge.cli.command.Registry;
import com.gitforge.cli.config.CliConfig;
import com.gitforge.cli.config.Credentials;
import com.gitforge.cli.options.GlobalOptions;
import com.gitforge.cli.output.Output;
import com.gitforge.cli.security.AuditLog;
import com.gitforge.cli.security.Redactor;
import com.gitforge.cli.security.SandboxPath;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The one path from a command line to an exit status.
 *
 * <p>Everything that must be true of every command is enforced here rather than
 * inside the commands, because a rule a command has to remember is a rule that
 * one command will forget:
 *
 * <ul>
 *   <li>read-only mode refuses a mutating command <em>before</em> it is entered,
 *       so nothing is validated, fetched or opened on the way to declining;
 *   <li>every failure becomes one {@link CliException} with a code and an exit
 *       status, rendered in one place, so JSON and text cannot disagree;
 *   <li>every run is audited, including the ones that failed — a refusal is
 *       exactly the kind of event a log exists to hold;
 *   <li>{@code --timeout} bounds the whole command, not each operation inside it.
 * </ul>
 *
 * <p>This class returns an exit code and never calls {@code System.exit}, so a
 * test can run a whole command in-process and read the result. The one call to
 * {@code exit} is in {@link Main}.
 */
public final class Cli {

    private final PrintStream out;
    private final PrintStream err;
    private final Map<String, String> environment;
    private final Path home;

    public Cli(PrintStream out, PrintStream err, Map<String, String> environment, Path home) {
        this.out = out;
        this.err = err;
        this.environment = environment;
        this.home = home;
    }

    /** Runs one command line and returns what the process should exit with. */
    public int run(String... argv) {
        GlobalOptions options;
        Redactor redactor = new Redactor();
        Output output = null;
        String commandName = "gitforge";
        List<String> arguments = List.of();

        try {
            options = GlobalOptions.parse(List.of(argv), environment);
            output = new Output(out, err, options, redactor);

            List<String> words = options.positional();
            if (words.isEmpty() || words.get(0).equals("help") || words.get(0).equals("--help")) {
                return help(output, words);
            }
            if (words.get(0).equals("version") || words.get(0).equals("--version")) {
                output.success("version", com.gitforge.cli.output.Json.map("version", version()),
                        List.of(), () -> output(out, "gitforge " + version()));
                return ExitCode.SUCCESS.number();
            }

            Registry.Match match = Registry.find(words);
            Command command = match.command();
            commandName = command.name();
            arguments = match.arguments();

            Path configDir = home.resolve(".gitforge");
            CliConfig config = new CliConfig(configDir.resolve("config"));
            Credentials credentials = new Credentials(configDir.resolve("credentials"), redactor);
            AuditLog audit = new AuditLog(configDir.resolve("audit.jsonl"), redactor);

            SandboxPath sandbox = new SandboxPath(sandboxRoot(options, config));
            Context context = new Context(
                    options, output, sandbox, config, credentials, audit, redactor, environment);

            // Before anything else the command might do.
            if (command.mutates()) {
                context.requireMutable(command.name());
            }

            context.out().trace("sandbox: " + sandbox.root());
            context.out().trace("command: " + command.name());

            Object result = withTimeout(options.timeoutSeconds(), () -> command.run(context, match.arguments()));

            Output renderer = output;
            renderer.success(command.name(), result, context.warnings(),
                    () -> command.describe(context, result));
            audit.record(command.name(), redacted(arguments), context.authorizationDecision(),
                    context.refsMoved(), ExitCode.SUCCESS.number());
            for (String problem : audit.problems()) {
                renderer.trace(problem);
            }
            return ExitCode.SUCCESS.number();

        } catch (CliException refused) {
            return report(output, redactor, commandName, arguments,
                    refused.code(), refused.getMessage(), refused.exitCode());
        } catch (com.gitforge.vcs.worktree.CheckoutBlockedException blocked) {
            // The working tree holds changes the checkout would destroy. The
            // repository is fine and the command is fine; the state cannot take
            // this operation, which is what CONFLICT means.
            return report(output, redactor, commandName, arguments,
                    "CONFLICT", String.valueOf(blocked.getMessage()), ExitCode.CONFLICT);
        } catch (com.gitforge.vcs.ref.RefException missing) {
            return report(output, redactor, commandName, arguments,
                    "NOT_FOUND", String.valueOf(missing.getMessage()), ExitCode.NOT_FOUND);
        } catch (IllegalArgumentException badInput) {
            // The engine validates with these; a bad ref name is the caller's
            // mistake, not an internal failure.
            return report(output, redactor, commandName, arguments,
                    "VALIDATION_FAILED", String.valueOf(badInput.getMessage()), ExitCode.USAGE);
        } catch (RuntimeException unexpected) {
            return report(output, redactor, commandName, arguments,
                    "INTERNAL_ERROR", describe(unexpected), ExitCode.FAILURE);
        }
    }

    private int report(
            Output output,
            Redactor redactor,
            String command,
            List<String> arguments,
            String code,
            String message,
            ExitCode exitCode) {

        if (output != null) {
            output.failure(command, code, message);
        } else {
            err.println("error: " + redactor.scrub(message));
        }
        try {
            new AuditLog(home.resolve(".gitforge").resolve("audit.jsonl"), redactor)
                    .record(command, redacted(arguments), "n/a", List.of(), exitCode.number());
        } catch (RuntimeException unloggable) {
            // Auditing a failure must not turn it into a different failure.
        }
        return exitCode.number();
    }

    /**
     * Bounds the command, not the individual calls inside it.
     *
     * <p>A per-operation timeout lets a command that makes twenty calls take
     * twenty times as long as the caller allowed. The number on the command line
     * is what the caller is willing to wait, so that is what is enforced.
     */
    private static Object withTimeout(int seconds, Callable<Object> work) {
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "gitforge-command");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<Object> future = executor.submit(work);
            return future.get(seconds, TimeUnit.SECONDS);
        } catch (TimeoutException tooSlow) {
            throw new CliException("TIMEOUT", ExitCode.TIMEOUT,
                    "The command did not finish within " + seconds + " seconds");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw CliException.failure(String.valueOf(cause == null ? failed.getMessage() : cause.getMessage()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw CliException.failure("Interrupted");
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Where the sandbox root comes from, most specific first.
     *
     * <p>Falling back to the working directory is what makes the CLI usable
     * without configuration, and it is still a real boundary: the root is fixed
     * when the process starts and nothing can widen it afterwards.
     */
    private static Path sandboxRoot(GlobalOptions options, CliConfig config) {
        if (options.sandbox() != null) {
            return options.sandbox();
        }
        Optional<String> configured = config.get("sandbox.root");
        if (configured.isPresent()) {
            return Path.of(configured.get());
        }
        return Path.of(System.getProperty("user.dir", "."));
    }

    private List<String> redacted(List<String> arguments) {
        return new ArrayList<>(arguments);
    }

    private static String describe(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }

    private int help(Output output, List<String> words) {
        List<String> topic = words.size() > 1 ? words.subList(1, words.size()) : List.of();
        Object listing = Registry.help(topic);
        output.success("help", listing, List.of(), () -> Registry.printHelp(output, topic));
        return ExitCode.SUCCESS.number();
    }

    private void output(PrintStream stream, String text) {
        stream.println(text);
    }

    /** The version this binary was built from. */
    public static String version() {
        String implementation = Cli.class.getPackage().getImplementationVersion();
        return implementation == null ? "2.0.15" : implementation;
    }
}
