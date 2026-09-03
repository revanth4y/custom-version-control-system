package com.gitforge.cli.options;

import com.gitforge.cli.CliException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The flags every command answers to.
 *
 * <p>Parsed once, before dispatch, so that {@code --json} means the same thing
 * on every command and no command has to remember to support it. A flag handled
 * per-command is a flag that is missing from one of them.
 *
 * <p>Parsing stops at the first {@code --}, after which everything is a positional
 * argument. That is what makes it possible to name a file {@code --json} without
 * the CLI trying to interpret it, and it is the only way a path argument can ever
 * be fully general.
 *
 * <p>Environment variables are read as defaults and the command line wins, with
 * one deliberate exception: {@code GITFORGE_READ_ONLY} cannot be turned off by a
 * flag. A safety setting that a later argument can quietly clear is not a safety
 * setting, and the shape of the mistake — a script appending arguments to a
 * command it did not write — is exactly the one read-only mode exists for.
 */
public final class GlobalOptions {

    private boolean json;
    private boolean quiet;
    private boolean verbose;
    private boolean noColor;
    private boolean dryRun;
    private boolean preview;
    private boolean assumeYes;
    private boolean noInput;
    private boolean readOnly;
    private String format;
    private String repo;
    private Path sandbox;
    private int timeoutSeconds = 120;
    private final List<String> positional = new ArrayList<>();

    private GlobalOptions() {
    }

    /**
     * Splits argv into global flags and everything else.
     *
     * @param args the arguments after the program name
     * @param env the process environment, for defaults
     */
    public static GlobalOptions parse(List<String> args, Map<String, String> env) {
        GlobalOptions options = new GlobalOptions();
        options.applyEnvironment(env);

        boolean literal = false;
        int i = 0;
        while (i < args.size()) {
            String arg = args.get(i);
            i++;

            if (literal || !arg.startsWith("--")) {
                options.positional.add(arg);
                continue;
            }
            if (arg.equals("--")) {
                literal = true;
                continue;
            }

            String name = arg;
            String inline = null;
            int equals = arg.indexOf('=');
            if (equals > 0) {
                name = arg.substring(0, equals);
                inline = arg.substring(equals + 1);
            }

            if (takesValue(name)) {
                String raw;
                if (inline != null) {
                    if (inline.isEmpty()) {
                        throw CliException.usage(name + " needs a value");
                    }
                    raw = inline;
                } else {
                    if (i >= args.size()) {
                        throw CliException.usage(name + " needs a value");
                    }
                    raw = args.get(i);
                    i++;
                }
                switch (name) {
                    case "--format" -> options.format = raw;
                    case "--repo" -> options.repo = raw;
                    case "--sandbox" -> options.sandbox = Path.of(raw);
                    case "--timeout" -> options.timeoutSeconds = positiveInt(raw);
                    default -> throw new IllegalStateException("Unhandled valued flag " + name);
                }
                continue;
            }

            switch (name) {
                case "--json" -> options.json = true;
                case "--quiet" -> options.quiet = true;
                case "--verbose" -> options.verbose = true;
                case "--no-color" -> options.noColor = true;
                case "--dry-run" -> options.dryRun = true;
                case "--preview" -> options.preview = true;
                case "--yes" -> options.assumeYes = true;
                case "--no-input" -> options.noInput = true;
                case "--read-only" -> options.readOnly = true;
                // Anything else is the command's own flag, and is passed through
                // untouched rather than guessed at here.
                default -> options.positional.add(arg);
            }
        }
        return options;
    }

    private static boolean takesValue(String name) {
        return switch (name) {
            case "--format", "--repo", "--sandbox", "--timeout" -> true;
            default -> false;
        };
    }

    private static int positiveInt(String raw) {
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed <= 0) {
                throw CliException.usage("--timeout must be a positive number of seconds");
            }
            return parsed;
        } catch (NumberFormatException notANumber) {
            throw CliException.usage("--timeout must be a whole number of seconds, not " + raw);
        }
    }

    private void applyEnvironment(Map<String, String> env) {
        if (truthy(env.get("GITFORGE_READ_ONLY"))) {
            readOnly = true;
        }
        if (truthy(env.get("GITFORGE_JSON"))) {
            json = true;
        }
        if (truthy(env.get("GITFORGE_NO_INPUT")) || truthy(env.get("CI"))) {
            noInput = true;
        }
        // NO_COLOR is a cross-tool convention: set at all means no colour.
        if (env.containsKey("NO_COLOR")) {
            noColor = true;
        }
        String sandboxRoot = env.get("GITFORGE_SANDBOX");
        if (sandboxRoot != null && !sandboxRoot.isBlank()) {
            sandbox = Path.of(sandboxRoot);
        }
        String repository = env.get("GITFORGE_REPO");
        if (repository != null && !repository.isBlank()) {
            repo = repository;
        }
    }

    private static boolean truthy(String value) {
        return value != null && (value.equals("1") || value.equalsIgnoreCase("true")
                || value.equalsIgnoreCase("yes"));
    }

    // ------------------------------------------------------------- accessors

    public boolean json() {
        return json;
    }

    public boolean quiet() {
        return quiet;
    }

    public boolean verbose() {
        return verbose;
    }

    public boolean noColor() {
        return noColor;
    }

    public boolean dryRun() {
        return dryRun;
    }

    public boolean preview() {
        return preview;
    }

    public boolean assumeYes() {
        return assumeYes;
    }

    public boolean noInput() {
        return noInput;
    }

    public boolean readOnly() {
        return readOnly;
    }

    public String format() {
        return format;
    }

    public String repo() {
        return repo;
    }

    public Path sandbox() {
        return sandbox;
    }

    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    /** The command words and their arguments, with global flags removed. */
    public List<String> positional() {
        return positional;
    }

    /** A description of the effective settings, for {@code --verbose} and previews. */
    public Map<String, Object> describe() {
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("json", json);
        described.put("quiet", quiet);
        described.put("verbose", verbose);
        described.put("dryRun", dryRun);
        described.put("preview", preview);
        described.put("readOnly", readOnly);
        described.put("noInput", noInput);
        described.put("timeoutSeconds", timeoutSeconds);
        return described;
    }
}
