package com.gitforge.vcs.ref;

import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Signature;
import com.gitforge.vcs.object.Tag;
import com.gitforge.vcs.object.VcsObject;
import com.gitforge.vcs.repository.RepositoryLock;
import com.gitforge.vcs.storage.ObjectStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The rules that govern tags, layered over raw reference storage.
 *
 * <p>Two things live here that the store below deliberately does not know about.
 * The first is that a tag must name an object this repository actually holds — the
 * store takes an id and writes it, and whether anything is there is a rule imposed
 * from above, exactly as it is for branches. The second is peeling: the store
 * returns whatever a tag points at, and turning that into the commit a caller
 * usually wants means following tag objects until something else appears.
 *
 * <p><strong>Tags do not move.</strong> There is no update operation here and none
 * below it. Re-pointing a tag means deleting it and creating it again, which is
 * two deliberate acts rather than one careless one. The reason is the same one
 * that made push fast-forward-only: there is no reflog, so the object a moved tag
 * used to name may become collectible, and a reference that silently stops
 * describing what it described is worse than one that refuses to change.
 *
 * <p><strong>Creating an annotated tag writes an object first.</strong> Between
 * writing that object and publishing the ref it is reachable from nothing, which
 * is precisely the window a sweep would read as garbage — the same window a commit
 * has between its first blob and its branch update. The whole sequence therefore
 * runs inside one {@link RepositoryLock#shared} call.
 */
public final class TagService {

    /**
     * How many tag objects one peel will follow before giving up.
     *
     * <p>A cycle is impossible for the same reason it is impossible among commits:
     * a tag names its target inside the bytes that determine its own id, so being
     * its own ancestor would require its hash to appear in the input to that hash.
     * This ceiling is therefore not a cycle guard but a bound on absurdity — a
     * chain this long is a mistake or an attack, and either way following it
     * further tells nobody anything.
     */
    public static final int MAX_PEEL_DEPTH = 32;

    private final RefStore refStore;
    private final ObjectStore objectStore;
    private final RepositoryLock lock;

    public TagService(RefStore refStore, ObjectStore objectStore) {
        this(refStore, objectStore, new RepositoryLock());
    }

    public TagService(RefStore refStore, ObjectStore objectStore, RepositoryLock lock) {
        if (refStore == null || objectStore == null) {
            throw new IllegalArgumentException("Tag service requires a reference store and an object store");
        }
        if (lock == null) {
            throw new IllegalArgumentException("Tag service requires a repository lock");
        }
        this.refStore = refStore;
        this.objectStore = objectStore;
        this.lock = lock;
    }

    /**
     * Creates a lightweight tag: a name and an id, and nothing else.
     *
     * <p>No object is written, so the only hazard is the one branch creation has —
     * naming an object a sweep was about to collect — and the shared lock closes it
     * the same way.
     *
     * @throws RefException if the name is invalid, the tag exists, or the target is
     *     not an object in this repository
     */
    public void createLightweight(String name, ObjectId target) {
        TagName.validate(name);
        lock.shared(() -> {
            requireExistingObject(target);
            refStore.createTag(name, target);
        });
    }

    /**
     * Creates an annotated tag: a tag object, and a ref naming it.
     *
     * <p>The order is the point. The object is written first and is unreachable
     * until the last line, so both steps are inside one shared lock and no sweep can
     * run between them.
     *
     * @return the tag object that was written
     * @throws RefException if the name is invalid, the tag exists, or the target is
     *     not an object in this repository
     */
    public Tag createAnnotated(String name, ObjectId target, Signature tagger, String message) {
        TagName.validate(name);
        if (tagger == null) {
            throw new RefException("An annotated tag needs a tagger");
        }
        if (message == null || message.isBlank()) {
            throw new RefException("An annotated tag needs a message");
        }

        return lock.shared(() -> {
            VcsObject targetObject = requireExistingObject(target);

            // Refused before the object is written rather than after, so a rejected
            // creation leaves nothing behind at all.
            if (refStore.tagExists(name)) {
                throw new RefException("Tag already exists: " + name);
            }

            Tag tag = new Tag(target, targetObject.type(), name, tagger, message);
            objectStore.write(tag);
            refStore.createTag(name, tag.id());
            return tag;
        });
    }

    /** Every tag name, sorted. */
    public List<String> listTags() {
        return refStore.listTags();
    }

    /** What a tag points at directly — a tag object for an annotated tag. */
    public Optional<ObjectId> getTag(String name) {
        return refStore.getTag(name);
    }

    public boolean tagExists(String name) {
        return refStore.tagExists(name);
    }

    /**
     * The annotated tag object a tag names, or empty when the tag is lightweight
     * or absent.
     */
    public Optional<Tag> annotationOf(String name) {
        return refStore.getTag(name).flatMap(this::readTag);
    }

    /**
     * What a tag ultimately names, following tag objects until something else
     * appears.
     *
     * <p>For a lightweight tag this is the id the ref holds. For an annotated tag
     * it is the target, and for a tag naming a tag it is whatever lies at the end
     * of the chain.
     *
     * @throws RefException if the chain is longer than {@link #MAX_PEEL_DEPTH}, or
     *     if an object in it is missing
     */
    public Optional<ObjectId> peel(String name) {
        return refStore.getTag(name).map(this::peelObject);
    }

    /**
     * As {@link #peel(String)}, starting from an id rather than a tag name.
     *
     * <p>Public because revision resolution needs it: {@code v1.0} used where a
     * commit is expected should produce the commit, not the tag object that names
     * it.
     */
    public ObjectId peelObject(ObjectId id) {
        ObjectId current = id;
        for (int depth = 0; depth <= MAX_PEEL_DEPTH; depth++) {
            Optional<Tag> tag = readTag(current);
            if (tag.isEmpty()) {
                return current;
            }
            current = tag.get().target();
        }
        throw new RefException(
                "Tag chain from " + id + " is deeper than " + MAX_PEEL_DEPTH + " objects");
    }

    /**
     * Every object a tag speaks for: the tag objects in its chain, then the object
     * at the end.
     *
     * <p>Exposed for callers that need the whole chain rather than only its
     * conclusion, such as describing a tag without reading it twice.
     */
    public List<ObjectId> chainOf(String name) {
        Optional<ObjectId> start = refStore.getTag(name);
        if (start.isEmpty()) {
            return List.of();
        }
        List<ObjectId> chain = new ArrayList<>();
        ObjectId current = start.get();
        for (int depth = 0; depth <= MAX_PEEL_DEPTH; depth++) {
            chain.add(current);
            Optional<Tag> tag = readTag(current);
            if (tag.isEmpty()) {
                return List.copyOf(chain);
            }
            current = tag.get().target();
        }
        throw new RefException(
                "Tag chain for " + name + " is deeper than " + MAX_PEEL_DEPTH + " objects");
    }

    /**
     * Removes a tag.
     *
     * <p>The ref only. Whatever it named stays stored, exactly as deleting a branch
     * leaves its commits: this drops a reference, and reclaiming storage remains a
     * separate thing somebody asks for. Unlike creation this needs no lock, for the
     * same reason branch deletion needs none — removing a reference can only ever
     * make a later sweep collect more, never make it collect something live.
     *
     * @return true if a tag was removed
     */
    public boolean deleteTag(String name) {
        return refStore.deleteTag(name);
    }

    private Optional<Tag> readTag(ObjectId id) {
        return objectStore.read(id)
                .filter(Tag.class::isInstance)
                .map(Tag.class::cast);
    }

    private VcsObject requireExistingObject(ObjectId target) {
        if (target == null) {
            throw new RefException("A tag must point at an object");
        }
        return objectStore.read(target).orElseThrow(() ->
                new RefException("Cannot tag an object that does not exist: " + target));
    }
}
