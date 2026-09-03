package com.gitforge.cli.output;

import com.gitforge.cli.CliException;

import java.util.List;
import java.util.Map;

/**
 * {@code --format} for the shell one-liner case.
 *
 * <p>Deliberately tiny: {@code {field}} is replaced by that field's value, and
 * {@code {a.b}} walks into nested maps. There are no conditionals, no loops and
 * no function calls, because a template language in a CLI becomes a thing people
 * have to learn and debug, and the moment output needs real processing
 * {@code --json} and a real tool are the better answer.
 *
 * <p>An unknown field is an error rather than an empty string. A script whose
 * template silently produced nothing would keep running with a blank where a
 * commit id should be, and that failure is much harder to find later than a
 * refusal now.
 */
public final class Format {

    private Format() {
    }

    public static String apply(String template, Object data) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c != '{') {
                out.append(c);
                i++;
                continue;
            }
            int close = template.indexOf('}', i);
            if (close < 0) {
                throw CliException.usage("Unclosed '{' in --format");
            }
            String field = template.substring(i + 1, close);
            out.append(render(lookup(data, field, template)));
            i = close + 1;
        }
        return out.toString();
    }

    private static Object lookup(Object data, String field, String template) {
        Object current = data;
        for (String segment : field.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                throw CliException.usage("No field '" + field + "' in this command's output");
            }
            if (!map.containsKey(segment)) {
                throw CliException.usage("No field '" + field + "' in this command's output");
            }
            current = map.get(segment);
        }
        return current;
    }

    private static String render(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof List<?> list) {
            StringBuilder joined = new StringBuilder();
            for (Object element : list) {
                if (joined.length() > 0) {
                    joined.append(' ');
                }
                joined.append(render(element));
            }
            return joined.toString();
        }
        if (value instanceof Map<?, ?>) {
            return Json.write(value);
        }
        return String.valueOf(value);
    }
}
