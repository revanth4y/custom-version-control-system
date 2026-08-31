package com.gitforge.vcsapi.dto;

import java.time.Instant;
import java.util.List;

/**
 * The result of re-reading and re-hashing a repository's stored objects.
 *
 * <p>Every object counted in {@code verified} was read back from the store,
 * decompressed, parsed, and hashed again over its canonical framed form
 * {@code <type> <length>\0<payload>}; the resulting digest was compared with the
 * id the object is filed under. Nothing here is inferred from a file listing, a
 * count, or a database row.
 *
 * <p>Corruption is a <em>result</em>, not a failure: a scan that finds damage
 * answered the question it was asked, so it is reported in this body with a 200
 * rather than as a server error.
 *
 * @param storedObjects how many objects the store holds, from the same
 *     enumeration the scan verified — so the two figures cannot disagree
 * @param verified how many were actually read back and re-hashed; below
 *     {@code storedObjects} only when {@code truncated}
 * @param damaged the objects that failed, empty when everything passed
 * @param healthy true when every verified object matched its id, false when any
 *     did not, and <strong>null when nothing was verified</strong> — an empty
 *     repository has not been shown to be healthy, and saying so would be a
 *     claim this scan never established
 * @param truncated true when the store holds more objects than one request
 *     verifies, in which case the result describes only the objects checked
 * @param durationMs how long the verification itself took
 */
public record IntegrityReport(
        long storedObjects,
        int verified,
        List<DamagedObject> damaged,
        Boolean healthy,
        boolean truncated,
        Instant checkedAt,
        long durationMs) {

    /**
     * One object that did not verify.
     *
     * @param id the full object id, which is safe to disclose: it is already how
     *     the rest of the API addresses objects
     * @param detail a fixed phrase chosen from a closed vocabulary. Deliberately
     *     not the underlying exception message — those are written for a log and
     *     may carry storage particulars a client has no business seeing.
     */
    public record DamagedObject(String id, Reason reason, String detail) {
    }

    /** The closed set of ways an object can fail verification. */
    public enum Reason {

        /** The bytes rebuilt into an object, but it hashes to a different id. */
        HASH_MISMATCH("the stored bytes hash to a different id"),

        /** The bytes could not be decompressed, or did not parse as an object. */
        UNREADABLE("the stored bytes could not be decompressed or parsed"),

        /** Enumerated, then gone before it could be read. */
        MISSING("the object is no longer present in the store");

        private final String detail;

        Reason(String detail) {
            this.detail = detail;
        }

        public String detail() {
            return detail;
        }
    }
}
