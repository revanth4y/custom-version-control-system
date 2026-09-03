package com.gitforge.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the real CLI in-process and captures what it did.
 *
 * <p>The whole tool is exercised: the same dispatcher, the same flag parsing, the
 * same sandbox, the same commands. Only the three things a test must control are
 * substituted — the streams, the environment and the home directory — and
 * {@link Cli} takes all three as parameters precisely so that this is possible
 * without a seam anywhere else.
 *
 * <p>Nothing here reaches into a command. A test that constructed a command
 * directly would prove the command works when called the way the test calls it,
 * which is not the question.
 */
final class CliHarness {

    private final Path sandbox;
    private final Path home;
    private final Map<String, String> environment = new HashMap<>();

    CliHarness(Path sandbox, Path home) {
        this.sandbox = sandbox;
        this.home = home;
    }

    CliHarness with(String name, String value) {
        environment.put(name, value);
        return this;
    }

    /** What one command line produced. */
    record Result(int exitCode, String out, String err) {

        boolean succeeded() {
            return exitCode == ExitCode.SUCCESS.number();
        }

        /** Everything printed, for assertions about what must never appear. */
        String everything() {
            return out + "\n" + err;
        }
    }

    /**
     * Runs a command line inside the sandbox.
     *
     * <p>{@code --sandbox} is supplied so a test never depends on the process
     * working directory, which JUnit shares between tests and cannot change.
     */
    Result run(String... args) {
        return runIn(sandbox, args);
    }

    /** Runs with a specific working directory, for the discovery cases. */
    Result runIn(Path workingDirectory, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        List<String> argv = new ArrayList<>();
        argv.add("--sandbox");
        argv.add(sandbox.toString());
        argv.addAll(List.of(args));

        String previous = System.getProperty("user.dir");
        try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
                PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {

            // The sandbox resolves relative paths against the working directory,
            // and a test needs to stand somewhere specific to exercise that.
            System.setProperty("user.dir", workingDirectory.toString());
            int code = new Cli(outStream, errStream, Map.copyOf(environment), home)
                    .run(argv.toArray(String[]::new));
            outStream.flush();
            errStream.flush();
            return new Result(code, out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8));
        } finally {
            if (previous != null) {
                System.setProperty("user.dir", previous);
            }
        }
    }
}
