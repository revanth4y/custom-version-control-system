package com.gitforge.vcs.object;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Who did something, and when.
 *
 * <p>The zone offset is retained alongside the instant rather than being folded
 * away, because it is part of what the author actually recorded: the same moment
 * written in two different offsets is two different commits. Discarding it would
 * silently merge commits that are not the same.
 *
 * <p>Serialized as {@code Name <email> <epoch-seconds> <±HHMM>}.
 */
public record Signature(String name, String email, Instant timestamp, ZoneOffset offset) {

    private static final DateTimeFormatter OFFSET_FORMAT = DateTimeFormatter.ofPattern("xx");

    public Signature {
        requireClean(name, "name");
        requireClean(email, "email");
        if (timestamp == null) {
            throw new IllegalArgumentException("Signature timestamp must not be null");
        }
        if (offset == null) {
            throw new IllegalArgumentException("Signature offset must not be null");
        }
    }

    /** A signature at the given instant in UTC. */
    public static Signature of(String name, String email, Instant timestamp) {
        return new Signature(name, email, timestamp, ZoneOffset.UTC);
    }

    /** The canonical text form written into a commit. */
    public String format() {
        return name + " <" + email + "> " + timestamp.getEpochSecond() + " " + OFFSET_FORMAT.format(offset);
    }

    /**
     * Parses the canonical form.
     *
     * @throws CorruptObjectException if the text is not a well-formed signature
     */
    public static Signature parse(String text) {
        int emailStart = text.indexOf('<');
        int emailEnd = text.indexOf('>', emailStart + 1);
        if (emailStart < 1 || emailEnd < 0) {
            throw new CorruptObjectException("Signature is missing an <email>: " + text);
        }

        String name = text.substring(0, emailStart).trim();
        String email = text.substring(emailStart + 1, emailEnd);
        String[] time = text.substring(emailEnd + 1).trim().split(" ");
        if (time.length != 2) {
            throw new CorruptObjectException("Signature is missing a timestamp and offset: " + text);
        }

        try {
            return new Signature(
                    name,
                    email,
                    Instant.ofEpochSecond(Long.parseLong(time[0])),
                    ZoneOffset.of(time[1]));
        } catch (NumberFormatException | java.time.DateTimeException ex) {
            throw new CorruptObjectException("Signature has an unreadable timestamp: " + text, ex);
        } catch (IllegalArgumentException ex) {
            throw new CorruptObjectException("Signature is malformed: " + text, ex);
        }
    }

    private static void requireClean(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Signature " + field + " must not be empty");
        }
        // These characters delimit fields in the serialized form, so allowing
        // them would let a name forge extra headers.
        if (value.indexOf('<') >= 0 || value.indexOf('>') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(
                    "Signature " + field + " must not contain '<', '>', a newline or a NUL: " + value);
        }
    }
}
