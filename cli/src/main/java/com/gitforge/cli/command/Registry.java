package com.gitforge.cli.command;

import com.gitforge.cli.CliException;
import com.gitforge.cli.output.Json;
import com.gitforge.cli.output.Output;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every command, and how a command line finds one.
 *
 * <p>Commands are registered under their full path — {@code "branch delete"}, not
 * {@code "branch"} with a switch inside — so the dispatcher can match the longest
 * path and hand the rest to the command as arguments. That keeps groups from
 * growing a parser of their own, and it makes {@code mutates()} a property of the
 * specific operation rather than of the whole group: {@code branch list} is not
 * a mutation just because {@code branch delete} is.
 *
 * <p>Matching is longest-first, so {@code commits/series} style two-word commands
 * win over their one-word prefix, and an unknown subcommand produces a message
 * naming the ones that exist rather than a generic refusal.
 */
public final class Registry {

    private static final Map<String, Command> COMMANDS = new TreeMap<>();

    static {
        LocalCommands.registerAll();
        RefCommands.registerAll();
        MergeCommand.registerAll();
        VerifyCommands.registerAll();
        ExplainCommands.registerAll();

        register(new SandboxCommands.Status());
        register(new SandboxCommands.Init());
        register(new SandboxCommands.Verify());
        register(new SandboxCommands.Policy());

        register(new ConfigCommands.Get());
        register(new ConfigCommands.Set());
        register(new ConfigCommands.List());
        register(new ConfigCommands.Unset());
    }

    private Registry() {
    }

    static void register(Command command) {
        COMMANDS.put(command.name(), command);
    }

    /** A command and the arguments left after its name. */
    public record Match(Command command, List<String> arguments) {
    }

    /**
     * Finds the command a command line names.
     *
     * <p>Tries the longest prefix first: three words, then two, then one. A group
     * name on its own is a usage error listing its subcommands, which is more
     * useful than "unknown command" when the caller typed half of one.
     */
    public static Match find(List<String> words) {
        for (int length = Math.min(3, words.size()); length >= 1; length--) {
            String candidate = String.join(" ", words.subList(0, length));
            Command command = COMMANDS.get(candidate);
            if (command != null) {
                return new Match(command, new ArrayList<>(words.subList(length, words.size())));
            }
        }

        String head = words.get(0);
        List<String> inGroup = COMMANDS.keySet().stream()
                .filter(name -> name.equals(head) || name.startsWith(head + " "))
                .toList();
        if (!inGroup.isEmpty()) {
            throw CliException.usage(
                    "'" + String.join(" ", words) + "' is not a command. Did you mean: "
                            + String.join(", ", inGroup) + "?");
        }
        throw CliException.usage(
                "Unknown command '" + head + "'. Run 'gitforge help' for the list.");
    }

    /** Every registered command name, sorted. */
    public static List<String> names() {
        return List.copyOf(COMMANDS.keySet());
    }

    /** Structured help, for {@code --json}. */
    public static Object help(List<String> topic) {
        String prefix = String.join(" ", topic);
        List<Map<String, Object>> listing = new ArrayList<>();
        COMMANDS.forEach((name, command) -> {
            if (prefix.isEmpty() || name.equals(prefix) || name.startsWith(prefix + " ")) {
                listing.add(Json.map(
                        "command", name,
                        "summary", command.summary(),
                        "usage", command.usage(),
                        "mutates", command.mutates()));
            }
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", com.gitforge.cli.Cli.version());
        data.put("commands", listing);
        return data;
    }

    /** Help for a person. */
    public static void printHelp(Output output, List<String> topic) {
        String prefix = String.join(" ", topic);
        output.line("gitforge " + com.gitforge.cli.Cli.version());
        output.line("");
        if (prefix.isEmpty()) {
            output.line("Usage: gitforge <command> [arguments] [flags]");
            output.line("");
            output.line("Global flags:");
            output.line("  --json               machine-readable output");
            output.line("  --quiet              print only the primary value");
            output.line("  --verbose            trace what is happening");
            output.line("  --format=<template>  render fields, such as --format='{commit}'");
            output.line("  --no-color           never colour the output");
            output.line("  --dry-run            plan the change without making it");
            output.line("  --preview            report exactly what would change");
            output.line("  --yes                consent to a destructive action in advance");
            output.line("  --no-input           never prompt; refuse instead");
            output.line("  --read-only          refuse anything that would change state");
            output.line("  --repo <owner/name>  the server repository to act on");
            output.line("  --sandbox <path>     the directory the CLI is confined to");
            output.line("  --timeout <seconds>  give up after this long");
            output.line("");
            output.line("Commands:");
        }
        COMMANDS.forEach((name, command) -> {
            if (prefix.isEmpty() || name.equals(prefix) || name.startsWith(prefix + " ")) {
                output.line(String.format("  %-28s %s", name, command.summary()));
            }
        });
    }
}
