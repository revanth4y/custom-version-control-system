package com.gitforge.vcs.object;

import java.nio.charset.StandardCharsets;

/**
 * An annotated tag: a permanent, named reference to another object, with a
 * message and a tagger recorded alongside it.
 *
 * <p><strong>Why a tag is an object at all.</strong> A lightweight tag is only a
 * file under {@code refs/tags} holding an id, and it carries nothing else — no
 * message, no author, no time. An annotated tag has all three, and they have to
 * live somewhere that cannot be edited afterwards, because a release reference
 * whose note could be rewritten in place would say nothing reliable about what
 * was released. Putting them in a content-addressed object gets that for free:
 * the id is the SHA-1 of exactly these bytes, so changing the message does not
 * amend the tag, it produces a different one.
 *
 * <p><strong>Why the target is an id rather than an object.</strong> The same
 * reason a commit names its tree by id. The target's own id covers its content
 * and, transitively, everything beneath it, so a tag id authenticates the whole
 * history it points at without the tag needing to contain any of it.
 *
 * <p><strong>What a tag may point at.</strong> Any object, including another tag.
 * The type is recorded in the payload rather than inferred, so a reader knows
 * what it is about to dereference before reading it. A chain of tags is unusual
 * but legal, and everything that walks tags — peeling, and the garbage
 * collector's closure — follows it rather than assuming one hop.
 *
 * <p>Serialized form, deliberately the same shape as a commit's so that one
 * parser idiom serves both:
 *
 * <pre>
 *   object &lt;40 hex&gt;\n
 *   type &lt;blob|tree|commit|tag&gt;\n
 *   tag &lt;name&gt;\n
 *   tagger &lt;name&gt; &lt;email&gt; &lt;epoch&gt; &lt;offset&gt;\n
 *   \n
 *   &lt;message&gt;
 * </pre>
 */
public final class Tag implements VcsObject {

    private static final String OBJECT_FIELD = "object ";
    private static final String TYPE_FIELD = "type ";
    private static final String TAG_FIELD = "tag ";
    private static final String TAGGER_FIELD = "tagger ";

    private final ObjectId target;
    private final ObjectType targetType;
    private final String name;
    private final Signature tagger;
    private final String message;
    private final ObjectId id;

    /**
     * @param target the object this tag names, which may itself be a tag
     * @param targetType what {@code target} is, recorded so a reader knows what
     *     it is dereferencing before it reads it
     * @param name the tag's name, which is part of its identity: the same target
     *     tagged under two names is two different objects
     * @param message normalised to end with exactly one newline
     */
    public Tag(
            ObjectId target,
            ObjectType targetType,
            String name,
            Signature tagger,
            String message) {

        if (target == null) {
            throw new IllegalArgumentException("Tag must reference a target object");
        }
        if (targetType == null) {
            throw new IllegalArgumentException("Tag must record the type of its target");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tag must have a name");
        }
        // A newline would forge a header line when serialized, so this is a
        // correctness rule about the format rather than a naming preference.
        // TagName rejects these too; this is the independent check, because a
        // Tag can be constructed without going through that class.
        if (name.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Tag name must not contain a newline: " + name);
        }
        if (tagger == null) {
            throw new IllegalArgumentException("Tag must have a tagger");
        }
        if (message == null) {
            throw new IllegalArgumentException("Tag message must not be null");
        }

        this.target = target;
        this.targetType = targetType;
        this.name = name;
        this.tagger = tagger;
        this.message = normaliseMessage(message);
        this.id = ObjectFormat.computeId(ObjectType.TAG, serializePayload());
    }

    @Override
    public ObjectType type() {
        return ObjectType.TAG;
    }

    @Override
    public byte[] payload() {
        return serializePayload();
    }

    @Override
    public ObjectId id() {
        return id;
    }

    /** The object this tag names. May be another tag. */
    public ObjectId target() {
        return target;
    }

    /** What {@link #target()} is, as recorded when the tag was written. */
    public ObjectType targetType() {
        return targetType;
    }

    /** The tag's name, which contributes to its identity. */
    public String name() {
        return name;
    }

    public Signature tagger() {
        return tagger;
    }

    /** The message, always ending in a newline. */
    public String message() {
        return message;
    }

    /** Whether this tag points at another tag, and so needs peeling more than once. */
    public boolean pointsAtATag() {
        return targetType == ObjectType.TAG;
    }

    private byte[] serializePayload() {
        return (OBJECT_FIELD + target.toHex() + '\n'
                + TYPE_FIELD + targetType.header() + '\n'
                + TAG_FIELD + name + '\n'
                + TAGGER_FIELD + tagger.format() + '\n'
                + '\n'
                + message)
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Ensures exactly one trailing newline when none is present.
     *
     * <p>Idempotent, so parsing and re-serializing reproduces the stored bytes
     * byte for byte and the object's id stays stable — the same rule commits use,
     * and for the same reason.
     */
    private static String normaliseMessage(String message) {
        return message.endsWith("\n") ? message : message + "\n";
    }

    /** Reads a tag back from its payload. */
    static Tag parse(byte[] payload) {
        String text = new String(payload, StandardCharsets.UTF_8);

        int headerEnd = text.indexOf("\n\n");
        if (headerEnd < 0) {
            throw new CorruptObjectException("Tag has no blank line separating headers from message");
        }
        String header = text.substring(0, headerEnd);
        String message = text.substring(headerEnd + 2);

        ObjectId target = null;
        ObjectType targetType = null;
        String name = null;
        Signature tagger = null;

        for (String line : header.split("\n", -1)) {
            if (line.startsWith(OBJECT_FIELD)) {
                if (target != null) {
                    throw new CorruptObjectException("Tag declares more than one target");
                }
                target = parseId(line.substring(OBJECT_FIELD.length()));
            } else if (line.startsWith(TYPE_FIELD)) {
                targetType = parseType(line.substring(TYPE_FIELD.length()));
            } else if (line.startsWith(TAG_FIELD)) {
                name = line.substring(TAG_FIELD.length());
            } else if (line.startsWith(TAGGER_FIELD)) {
                tagger = Signature.parse(line.substring(TAGGER_FIELD.length()));
            } else {
                throw new CorruptObjectException("Tag has an unrecognised header line: " + line);
            }
        }

        if (target == null) {
            throw new CorruptObjectException("Tag does not reference a target object");
        }
        if (targetType == null) {
            throw new CorruptObjectException("Tag does not record its target's type");
        }
        if (name == null || name.isBlank()) {
            throw new CorruptObjectException("Tag has no name");
        }
        if (tagger == null) {
            throw new CorruptObjectException("Tag has no tagger");
        }
        return new Tag(target, targetType, name, tagger, message);
    }

    private static ObjectId parseId(String hex) {
        try {
            return ObjectId.fromHex(hex);
        } catch (IllegalArgumentException ex) {
            throw new CorruptObjectException("Tag has an invalid target id: " + hex, ex);
        }
    }

    private static ObjectType parseType(String header) {
        try {
            return ObjectType.fromHeader(header);
        } catch (IllegalArgumentException ex) {
            throw new CorruptObjectException("Tag declares an unknown target type: " + header, ex);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Tag tag && id.equals(tag.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Tag[" + name + " -> " + target.abbreviate(8) + "]";
    }
}
