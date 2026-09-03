package com.gitforge.cli;

import java.nio.file.Path;
import java.util.Map;

/**
 * The process boundary, and nothing else.
 *
 * <p>Deliberately three lines. Everything a test would want to control — the
 * streams, the environment, the home directory — is a parameter of {@link Cli},
 * and the one thing a test cannot tolerate, {@code System.exit}, happens only
 * here. That is what lets the whole CLI be exercised in-process.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        Cli cli = new Cli(
                System.out,
                System.err,
                Map.copyOf(System.getenv()),
                Path.of(System.getProperty("user.home", ".")));
        System.exit(cli.run(args));
    }
}
