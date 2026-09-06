package com.gitforge.cli.security;

/**
 * Text a terminal will display rather than obey.
 *
 * <p>Almost everything the CLI prints came from somewhere else. A repository
 * name, a branch, a commit message, an issue title and its whole body, a
 * description, an error quoted back from a server — all of it is written by
 * other people and rendered straight into a terminal, and a terminal is not a
 * passive display. It runs what it is sent.
 *
 * <p>What that buys an attacker is not a shell; it is a lie. {@code ESC [ 2 K}
 * erases the line just written and {@code \r} returns to its start, so a
 * repository whose description begins with them can remove the line naming it
 * and print a different one. {@code ESC ] 0 ;} rewrites the window title.
 * Together they let a hostile repository decide what a person believes they are
 * looking at — that a private repository is public, that a destructive command
 * reported success, that a fingerprint matched. The output is then evidence of
 * nothing.
 *
 * <p>The other half is subtler and needs no control characters at all.
 * {@code U+202E} reverses display order, so a name can be spelled one way and
 * shown another; this is the mechanism behind the 2021 "Trojan Source" work, and
 * it is what turns a name a reviewer approves into a different name than the one
 * that runs. Only the characters that reorder text are escaped here — the
 * zero-width joiner is left alone, because it is how emoji are assembled and
 * mangling those would be a cost with no attacker behind it.
 *
 * <p><strong>Newline and tab survive.</strong> An issue body is genuinely
 * multi-line and a table is genuinely aligned; escaping those would break every
 * honest use to inconvenience a dishonest one that gains almost nothing. A
 * newline lets an attacker add lines. It does not let them remove, overwrite or
 * reposition one, which is the difference between clutter and forgery.
 *
 * <p>An escaped character is shown as {@code <U+XXXX>}: visible, unambiguous
 * about how many characters were there, and impossible to mistake for the
 * original having worked.
 */
public final class TerminalText {

    private TerminalText() {
    }

    /**
     * Whether a character would be interpreted rather than displayed.
     *
     * <p>{@link Character#CONTROL} covers C0, DEL and C1 — including {@code 0x9B},
     * which is a one-byte CSI and would otherwise start a sequence without any
     * escape character in sight.
     */
    public static boolean isDangerous(char c) {
        if (c == '\n' || c == '\t') {
            return false;
        }
        return Character.getType(c) == Character.CONTROL || reordersText(c);
    }

    /**
     * The characters that change display order.
     *
     * <p>Named individually rather than by Unicode category. The category is
     * {@code FORMAT}, which also contains the zero-width joiner and the soft
     * hyphen, and neither of those can forge anything.
     */
    private static boolean reordersText(char c) {
        // Written as numbers on purpose. Spelling them as literals would put the
        // very characters this method exists to catch into this file, invisible
        // to anyone reviewing it - which is the attack, performed on ourselves.
        return c == 0x061C                       // arabic letter mark
                || c == 0x200E || c == 0x200F    // left-to-right, right-to-left mark
                || (c >= 0x202A && c <= 0x202E)  // embeddings and overrides
                || (c >= 0x2066 && c <= 0x2069); // isolates
    }

    /**
     * The text with anything a terminal would act on made visible instead.
     *
     * <p>Returns the argument itself when there is nothing to change, which is
     * almost always: this runs on every line the CLI prints, and the common case
     * should not allocate.
     */
    public static String neutralise(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        int first = -1;
        for (int i = 0; i < text.length(); i++) {
            if (isDangerous(text.charAt(i))) {
                first = i;
                break;
            }
        }
        if (first < 0) {
            return text;
        }

        StringBuilder out = new StringBuilder(text.length() + 16);
        out.append(text, 0, first);
        for (int i = first; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isDangerous(c)) {
                out.append(String.format("<U+%04X>", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
