package com.gitforge.cli.command;

import com.gitforge.cli.CliException;
import com.gitforge.cli.Context;
import com.gitforge.cli.config.CliConfig;
import com.gitforge.cli.output.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settings that persist between commands.
 *
 * <p>Reading an unset key is not an error — a script asking whether something is
 * configured should get an answer, not an exception — but writing an unknown key
 * is, because that is always a typo and silently accepting it produces a setting
 * that never takes effect.
 */
public final class ConfigCommands {

    private ConfigCommands() {
    }

    public static final class Get implements Command {

        @Override
        public String name() {
            return "config get";
        }

        @Override
        public String summary() {
            return "Show one setting";
        }

        @Override
        public String usage() {
            return "gitforge config get <key>";
        }

        @Override
        public Object run(Context context, java.util.List<String> args) {
            if (args.size() != 1) {
                throw CliException.usage(usage());
            }
            String key = args.get(0);
            return Json.map(
                    "key", key,
                    "value", context.config().get(key).orElse(null),
                    "set", context.config().get(key).isPresent());
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            Object value = data.get("value");
            context.out().line(value == null ? "" : String.valueOf(value));
        }
    }

    public static final class Set implements Command {

        @Override
        public String name() {
            return "config set";
        }

        @Override
        public String summary() {
            return "Change one setting";
        }

        @Override
        public String usage() {
            return "gitforge config set <key> <value>";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public Object run(Context context, java.util.List<String> args) {
            if (args.size() != 2) {
                throw CliException.usage(usage());
            }
            String key = args.get(0);
            String value = args.get(1);
            String previous = context.config().get(key).orElse(null);

            if (context.options().dryRun()) {
                return Json.map("key", key, "from", previous, "to", value, "mutated", false);
            }
            context.config().set(key, value);
            return Json.map("key", key, "from", previous, "to", value, "mutated", true);
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line(data.get("key") + " = " + data.get("to"));
        }
    }

    public static final class List implements Command {

        @Override
        public String name() {
            return "config list";
        }

        @Override
        public String summary() {
            return "Show every known setting and its value";
        }

        @Override
        public String usage() {
            return "gitforge config list";
        }

        @Override
        public Object run(Context context, java.util.List<String> args) {
            java.util.List<Map<String, Object>> rows = new ArrayList<>();
            for (String key : CliConfig.knownKeys()) {
                rows.add(Json.map(
                        "key", key,
                        "value", context.config().get(key).orElse(null),
                        "set", context.config().get(key).isPresent(),
                        "description", CliConfig.KNOWN.get(key)));
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("file", context.config().file().toString());
            data.put("settings", rows);
            return data;
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            for (Object row : (java.util.List<?>) data.get("settings")) {
                Map<?, ?> setting = (Map<?, ?>) row;
                Object value = setting.get("value");
                context.out().line(String.format("  %-14s %s",
                        setting.get("key"), value == null ? "(unset)" : value));
            }
        }
    }

    public static final class Unset implements Command {

        @Override
        public String name() {
            return "config unset";
        }

        @Override
        public String summary() {
            return "Remove one setting";
        }

        @Override
        public String usage() {
            return "gitforge config unset <key>";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public Object run(Context context, java.util.List<String> args) {
            if (args.size() != 1) {
                throw CliException.usage(usage());
            }
            String key = args.get(0);
            String previous = context.config().get(key).orElse(null);
            if (context.options().dryRun()) {
                return Json.map("key", key, "was", previous, "removed", previous != null, "mutated", false);
            }
            boolean removed = context.config().unset(key);
            return Json.map("key", key, "was", previous, "removed", removed, "mutated", removed);
        }

        @Override
        public void describe(Context context, Object result) {
            Map<?, ?> data = (Map<?, ?>) result;
            context.out().line(Boolean.TRUE.equals(data.get("removed"))
                    ? "Removed " + data.get("key")
                    : data.get("key") + " was not set");
        }
    }
}
