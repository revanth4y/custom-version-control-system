package com.gitforge.cli.output;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON, written the same way twice.
 *
 * <p>Hand-written rather than handed to a library, for one reason: determinism
 * is the contract here, and a serializer that is free to choose field order,
 * number formatting or escaping is free to break a golden file on a version
 * bump. What this emits depends only on what it is given.
 *
 * <p>The rules it keeps:
 *
 * <ul>
 *   <li>maps are written in insertion order, so callers decide field order and
 *       it never changes underneath them;
 *   <li>timestamps are RFC 3339 in UTC, always — a local zone would make output
 *       depend on the machine that produced it;
 *   <li>numbers are written without locale, so a comma decimal separator cannot
 *       appear and turn one field into two;
 *   <li>escaping is explicit, including the control characters below 0x20 that
 *       would otherwise produce invalid JSON.
 * </ul>
 */
public final class Json {

    private static final DateTimeFormatter RFC_3339 =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private Json() {
    }

    /** An instant as RFC 3339 in UTC, or null. */
    public static String time(Instant instant) {
        return instant == null ? null : RFC_3339.format(instant);
    }

    /** A convenience for building ordered maps without four lines of ceremony. */
    public static Map<String, Object> map(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("Keys and values must pair up");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return map;
    }

    /** The value as compact JSON. */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out, -1, 0);
        return out.toString();
    }

    /** The value as JSON indented by two spaces, for a person to read. */
    public static String pretty(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out, 2, 0);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out, int indent, int depth) {
        switch (value) {
            case null -> out.append("null");
            case String s -> quote(s, out);
            case Boolean b -> out.append(b.toString());
            case Instant i -> quote(time(i), out);
            case Integer n -> out.append(n.toString());
            case Long n -> out.append(n.toString());
            case Double d -> out.append(number(d));
            case Float f -> out.append(number(f.doubleValue()));
            case Number n -> out.append(n.toString());
            case Map<?, ?> m -> writeMap(m, out, indent, depth);
            case Collection<?> c -> writeList(c, out, indent, depth);
            case Object[] a -> writeList(java.util.Arrays.asList(a), out, indent, depth);
            default -> quote(String.valueOf(value), out);
        }
    }

    /**
     * A double without a locale and without an exponent.
     *
     * <p>{@code String.valueOf(0.1666)} is fine, but a ratio that happens to be
     * very small would come out as {@code 1.0E-4} and surprise a consumer
     * expecting a plain decimal. Rounding to six places also keeps two runs over
     * identical state byte-identical, which a raw binary double does not
     * guarantee across platforms.
     */
    private static String number(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "null";
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return new java.math.BigDecimal(value)
                .setScale(6, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private static void writeMap(Map<?, ?> map, StringBuilder out, int indent, int depth) {
        if (map.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            newline(out, indent, depth + 1);
            quote(String.valueOf(entry.getKey()), out);
            out.append(':');
            if (indent >= 0) {
                out.append(' ');
            }
            write(entry.getValue(), out, indent, depth + 1);
        }
        newline(out, indent, depth);
        out.append('}');
    }

    private static void writeList(Collection<?> list, StringBuilder out, int indent, int depth) {
        if (list.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append('[');
        boolean first = true;
        for (Object element : list) {
            if (!first) {
                out.append(',');
            }
            first = false;
            newline(out, indent, depth + 1);
            write(element, out, indent, depth + 1);
        }
        newline(out, indent, depth);
        out.append(']');
    }

    private static void newline(StringBuilder out, int indent, int depth) {
        if (indent < 0) {
            return;
        }
        out.append('\n');
        out.append(" ".repeat(indent * depth));
    }

    private static void quote(String text, StringBuilder out) {
        if (text == null) {
            out.append("null");
            return;
        }
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
