package com.gitforge.cli.output;

import com.gitforge.cli.options.GlobalOptions;
import com.gitforge.cli.security.Redactor;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;

/**
 * Everything the CLI says, and the only way it says it.
 *
 * <p>Commands hand this a result and a way to describe it; they never touch
 * {@code System.out}. That indirection is what makes three guarantees hold
 * everywhere rather than in the places somebody remembered:
 *
 * <ul>
 *   <li>redaction runs on every stream, so a token cannot escape through a
 *       command that did not know it was carrying one;
 *   <li>{@code --json} is honoured by every command, because the choice of
 *       renderer is made here and not by the command;
 *   <li>{@code --quiet} means the same thing everywhere: the identifier a script
 *       would want, and nothing else.
 * </ul>
 *
 * <p>Colour is off unless the stream is a terminal. A pipe receiving escape
 * codes is a pipe whose consumer has to strip them, and the consumer is usually
 * a regular expression that gets it slightly wrong.
 */
public final class Output {

    private final PrintStream out;
    private final PrintStream err;
    private final GlobalOptions options;
    private final Redactor redactor;
    private final boolean colour;

    public Output(PrintStream out, PrintStream err, GlobalOptions options, Redactor redactor) {
        this.out = out;
        this.err = err;
        this.options = options;
        this.redactor = redactor;
        this.colour = !options.noColor() && System.console() != null;
    }

    /**
     * A successful result.
     *
     * @param command the command path, for the envelope
     * @param data the structured result, rendered as JSON under {@code --json}
     * @param warnings non-fatal notes
     * @param text how to say the same thing to a person; not called under
     *     {@code --json} or {@code --quiet}
     */
    public void success(String command, Object data, List<String> warnings, Runnable text) {
        if (options.json()) {
            print(out, Json.pretty(Envelope.ok(command, data, warnings).fields()));
            return;
        }
        if (options.quiet()) {
            printQuiet(data);
            return;
        }
        if (options.format() != null) {
            print(out, Format.apply(options.format(), data));
            return;
        }
        if (text != null) {
            text.run();
        }
        for (String warning : warnings == null ? List.<String>of() : warnings) {
            warn(warning);
        }
    }

    /**
     * A failure.
     *
     * <p>Always on stderr, even under {@code --json}: a script redirecting stdout
     * to a file should get the data there and the diagnosis somewhere it will be
     * noticed, and a JSON parser reading a mixed stream is a bug waiting.
     */
    public void failure(String command, String code, String message) {
        if (options.json()) {
            print(err, Json.pretty(Envelope.error(command, code, message).fields()));
            return;
        }
        print(err, paint("error", "31") + ": " + message);
    }

    /** A line of ordinary output. */
    public void line(String text) {
        if (!options.quiet() && !options.json()) {
            print(out, text);
        }
    }

    /** A non-fatal note. Suppressed by {@code --quiet}, since it is not the answer. */
    public void warn(String text) {
        if (!options.quiet() && !options.json()) {
            print(err, paint("warning", "33") + ": " + text);
        }
    }

    /** Tracing, shown only under {@code --verbose}. Redacted like everything else. */
    public void trace(String text) {
        if (options.verbose() && !options.json()) {
            print(err, paint("trace", "90") + ": " + text);
        }
    }

    /**
     * The single value a script would want from a command.
     *
     * <p>Under {@code --quiet} this is all that is printed: a commit id, a branch
     * name, a count. Everything else is decoration, and a shell substitution
     * should not have to strip it.
     */
    private void printQuiet(Object data) {
        if (data instanceof Map<?, ?> map) {
            for (String key : List.of("id", "commit", "sha", "name", "count", "total")) {
                Object value = map.get(key);
                if (value != null) {
                    print(out, String.valueOf(value));
                    return;
                }
            }
        }
        if (data != null && !(data instanceof Map) && !(data instanceof List)) {
            print(out, String.valueOf(data));
        }
    }

    private void print(PrintStream stream, String text) {
        stream.println(redactor.scrub(text));
    }

    private String paint(String text, String code) {
        return colour ? "[" + code + "m" + text + "[0m" : text;
    }
}
