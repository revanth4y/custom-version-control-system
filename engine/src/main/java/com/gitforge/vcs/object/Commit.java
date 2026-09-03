package com.gitforge.vcs.object;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A snapshot of the repository, plus how it came to be.
 *
 * <p><strong>Why commits are immutable.</strong> A commit's id is the SHA-1 of
 * the bytes that describe it. Editing any field would change those bytes and so
 * produce a different id — meaning the edit does not modify the commit, it
 * creates a new one. Immutability is not a convention enforced by discipline
 * here; it is a consequence of identity being derived from content. That is what
 * makes history verifiable: a commit id, once known, always denotes exactly the
 * same snapshot and the same ancestry.
 *
 * <p><strong>Why identity spans tree, parents and metadata.</strong> The root
 * tree fixes <em>what</em> the repository contained; the parents fix <em>what it
 * followed</em>; the author, timestamps and message fix <em>who and why</em>.
 * Because the parents' ids are inside the hashed bytes, and each parent's id in
 * turn covers its own ancestry, a commit id transitively authenticates the whole
 * history behind it. Rewriting any ancestor changes every descendant's id, which
 * is precisely why history cannot be altered silently.
 *
 * <p><strong>Why history forms a DAG.</strong> A commit names its parents, so
 * edges point backwards in time — directed. It may name several parents, which is
 * how independent lines of development rejoin, so the shape is a graph rather
 * than a tree. And it cannot be acyclic by accident: for a cycle to exist a
 * commit would have to be its own ancestor, so its hash would have to appear
 * inside the very bytes being hashed. Acyclicity is enforced by SHA-1, not by a
 * runtime check.
 *
 * <p><strong>How merge commits are represented.</strong> A merge is simply a
 * commit with two or more parents, listed in the order they were combined:
 * parent 0 is the branch that was being merged into, parent 1 the branch merged
 * in. That ordering is part of the commit's identity — swapping the parents
 * yields a different commit. Nothing about the merge <em>algorithm</em> is needed
 * to represent one, which is why the representation lands here while the logic
 * that computes a merged tree comes later.
 *
 * <p>Serialized form:
 *
 * <pre>
 *   tree &lt;40 hex&gt;\n
 *   parent &lt;40 hex&gt;\n      (repeated, zero or more, in order)
 *   author &lt;name&gt; &lt;email&gt; &lt;epoch&gt; &lt;offset&gt;\n
 *   committer &lt;name&gt; &lt;email&gt; &lt;epoch&gt; &lt;offset&gt;\n
 *   \n
 *   &lt;message&gt;
 * </pre>
 */
public final class Commit implements VcsObject {

    private static final String TREE_FIELD = "tree ";
    private static final String PARENT_FIELD = "parent ";
    private static final String AUTHOR_FIELD = "author ";
    private static final String COMMITTER_FIELD = "committer ";

    private final ObjectId tree;
    private final List<ObjectId> parents;
    private final Signature author;
    private final Signature committer;
    private final String message;
    private final ObjectId id;

    /**
     * @param tree the root tree: the repository state this commit captures
     * @param parents the commits this one follows, in significant order; empty
     *     for an initial commit, one for an ordinary commit, two or more for a
     *     merge
     * @param message normalised to end with exactly one newline
     */
    public Commit(
            ObjectId tree,
            List<ObjectId> parents,
            Signature author,
            Signature committer,
            String message) {

        if (tree == null) {
            throw new IllegalArgumentException("Commit must reference a root tree");
        }
        if (parents == null) {
            throw new IllegalArgumentException("Commit parents must not be null");
        }
        // Scanned explicitly rather than with contains(null): immutable lists
        // throw NullPointerException when queried with null instead of
        // answering false.
        for (ObjectId parent : parents) {
            if (parent == null) {
                throw new IllegalArgumentException("Commit parents must not contain null");
            }
        }
        if (author == null || committer == null) {
            throw new IllegalArgumentException("Commit must have an author and a committer");
        }
        if (message == null) {
            throw new IllegalArgumentException("Commit message must not be null");
        }

        this.tree = tree;
        // Order is significant and therefore preserved, never sorted: the first
        // parent identifies the line of development being continued.
        this.parents = List.copyOf(parents);
        this.author = author;
        this.committer = committer;
        this.message = normaliseMessage(message);
        this.id = ObjectFormat.computeId(ObjectType.COMMIT, serializePayload());
    }

    /** An ordinary commit, authored and committed by the same person. */
    public static Commit of(ObjectId tree, List<ObjectId> parents, Signature author, String message) {
        return new Commit(tree, parents, author, author, message);
    }

    @Override
    public ObjectType type() {
        return ObjectType.COMMIT;
    }

    @Override
    public byte[] payload() {
        return serializePayload();
    }

    @Override
    public ObjectId id() {
        return id;
    }

    public ObjectId tree() {
        return tree;
    }

    /** Parents in significant order. Empty for an initial commit. */
    public List<ObjectId> parents() {
        return parents;
    }

    public Signature author() {
        return author;
    }

    public Signature committer() {
        return committer;
    }

    /** The message, always ending in a newline. */
    public String message() {
        return message;
    }

    public boolean isInitial() {
        return parents.isEmpty();
    }

    public boolean isMerge() {
        return parents.size() > 1;
    }

    private byte[] serializePayload() {
        StringBuilder text = new StringBuilder();
        text.append(TREE_FIELD).append(tree.toHex()).append('\n');
        for (ObjectId parent : parents) {
            text.append(PARENT_FIELD).append(parent.toHex()).append('\n');
        }
        text.append(AUTHOR_FIELD).append(author.format()).append('\n');
        text.append(COMMITTER_FIELD).append(committer.format()).append('\n');
        text.append('\n');
        text.append(message);

        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Ensures exactly one trailing newline when none is present.
     *
     * <p>Idempotent, so parsing and re-serializing reproduces the stored bytes
     * byte for byte and the object's id stays stable. Messages that deliberately
     * end in blank lines keep them.
     */
    private static String normaliseMessage(String message) {
        return message.endsWith("\n") ? message : message + "\n";
    }

    /** Reads a commit back from its payload. */
    static Commit parse(byte[] payload) {
        String text = new String(payload, StandardCharsets.UTF_8);

        int headerEnd = text.indexOf("\n\n");
        if (headerEnd < 0) {
            throw new CorruptObjectException("Commit has no blank line separating headers from message");
        }
        String header = text.substring(0, headerEnd);
        String message = text.substring(headerEnd + 2);

        ObjectId tree = null;
        List<ObjectId> parents = new java.util.ArrayList<>();
        Signature author = null;
        Signature committer = null;

        for (String line : header.split("\n", -1)) {
            if (line.startsWith(TREE_FIELD)) {
                if (tree != null) {
                    throw new CorruptObjectException("Commit declares more than one tree");
                }
                tree = parseId(line.substring(TREE_FIELD.length()), "tree");
            } else if (line.startsWith(PARENT_FIELD)) {
                parents.add(parseId(line.substring(PARENT_FIELD.length()), "parent"));
            } else if (line.startsWith(AUTHOR_FIELD)) {
                author = Signature.parse(line.substring(AUTHOR_FIELD.length()));
            } else if (line.startsWith(COMMITTER_FIELD)) {
                committer = Signature.parse(line.substring(COMMITTER_FIELD.length()));
            } else {
                throw new CorruptObjectException("Commit has an unrecognised header line: " + line);
            }
        }

        if (tree == null) {
            throw new CorruptObjectException("Commit does not reference a tree");
        }
        if (author == null || committer == null) {
            throw new CorruptObjectException("Commit is missing an author or committer");
        }
        return new Commit(tree, parents, author, committer, message);
    }

    private static ObjectId parseId(String hex, String field) {
        try {
            return ObjectId.fromHex(hex);
        } catch (IllegalArgumentException ex) {
            throw new CorruptObjectException("Commit has an invalid " + field + " id: " + hex, ex);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Commit commit && id.equals(commit.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Commit[" + id.abbreviate(8) + ", " + parents.size() + " parents]";
    }
}
