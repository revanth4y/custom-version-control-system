package com.gitforge.cli.command;

import com.gitforge.cli.Context;

import java.util.List;

/**
 * One thing the CLI can be asked to do.
 *
 * <p>A command returns its result rather than printing it. That separation is
 * what lets one renderer serve both {@code --json} and human output from the
 * same execution — two code paths would eventually disagree about a number — and
 * it is what lets a test assert on structure instead of on formatting.
 *
 * <p>{@link #mutates()} is declared rather than inferred, and the dispatcher
 * uses it to enforce read-only mode before the command is entered. Declaring it
 * wrong is the one mistake this design cannot catch, so it is a single boolean
 * on the class rather than a condition somewhere inside it.
 */
public interface Command {

    /** The command path, such as {@code branch delete}. Used in help and the envelope. */
    String name();

    /** One line, shown by {@code help}. */
    String summary();

    /** How to invoke it, shown on a usage error. */
    String usage();

    /** Whether running this can change any state at all. */
    default boolean mutates() {
        return false;
    }

    /**
     * Does the work.
     *
     * @param context everything the command may reach
     * @param args the arguments after the command words
     * @return the structured result, rendered by the caller
     */
    Object run(Context context, List<String> args);

    /** How to describe the result to a person. Skipped under {@code --json}. */
    default void describe(Context context, Object result) {
        context.out().line(com.gitforge.cli.output.Json.pretty(result));
    }
}
