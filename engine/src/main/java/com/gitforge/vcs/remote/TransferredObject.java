package com.gitforge.vcs.remote;

import com.gitforge.vcs.object.ObjectFormat;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.ObjectType;
import com.gitforge.vcs.object.VcsObject;

import java.util.Base64;

/**
 * One object as it crosses the wire.
 *
 * <p>The payload travels uncompressed and base64-encoded, not in the form the
 * sender happened to have it on disk. That is the point: an object's id is the
 * SHA-1 of its <em>uncompressed</em> canonical representation, and compression is
 * "purely a storage concern" that "never influences an object's id". Sending the
 * canonical bytes means the receiver can recompute the id itself and does not
 * have to trust that the sender stored things the same way — or at all honestly.
 *
 * <p>Base64 rather than raw bytes because the rest of this API already carries
 * file content that way, through {@code ContentApiService.decode}, and a second
 * encoding for the same problem would be one more thing to get wrong.
 *
 * @param id the id the sender claims, as 40 hexadecimal characters
 * @param type blob, tree or commit
 * @param payload the canonical payload, base64-encoded
 */
public record TransferredObject(String id, String type, String payload) {

    public TransferredObject {
        if (id == null || id.isBlank()) {
            throw new RemoteException("A transferred object must carry an id");
        }
        if (type == null || type.isBlank()) {
            throw new RemoteException("A transferred object must carry a type");
        }
        if (payload == null) {
            throw new RemoteException("A transferred object must carry a payload");
        }
    }

    /** How this object should be sent. */
    public static TransferredObject of(VcsObject object) {
        return new TransferredObject(
                object.id().toHex(),
                object.type().header(),
                Base64.getEncoder().encodeToString(object.payload()));
    }

    /**
     * Rebuilds the object and proves it is the one that was claimed.
     *
     * <p>Every failure here means the same thing — what arrived is not what the
     * sender said arrived — so all of them are refused the same way. The id is
     * recomputed by {@link ObjectFormat}, the engine's own hashing, rather than
     * compared to anything the sender supplied beyond the claim itself.
     *
     * @throws RemoteException if the encoding, type, framing or hash does not hold
     */
    public VcsObject verified() {
        ObjectId claimed;
        try {
            claimed = ObjectId.fromHex(id);
        } catch (IllegalArgumentException ex) {
            throw new RemoteException("Transferred object has a malformed id: " + id, ex);
        }

        ObjectType objectType;
        try {
            objectType = ObjectType.fromHeader(type);
        } catch (RuntimeException ex) {
            throw new RemoteException("Transferred object " + id + " has an unknown type: " + type, ex);
        }

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException ex) {
            throw new RemoteException("Transferred object " + id + " is not valid base64", ex);
        }

        ObjectId actual = ObjectFormat.computeId(objectType, bytes);
        if (!actual.equals(claimed)) {
            throw new RemoteException(
                    "Transferred object claims to be " + id + " but hashes to " + actual.toHex());
        }

        try {
            return ObjectFormat.parse(ObjectFormat.frame(objectType, bytes));
        } catch (RuntimeException ex) {
            throw new RemoteException("Transferred object " + id + " could not be parsed", ex);
        }
    }

    /** Decoded payload length, for weighing a batch before accepting it. */
    public int payloadBytes() {
        try {
            return Base64.getDecoder().decode(payload).length;
        } catch (IllegalArgumentException ex) {
            throw new RemoteException("Transferred object " + id + " is not valid base64", ex);
        }
    }
}
