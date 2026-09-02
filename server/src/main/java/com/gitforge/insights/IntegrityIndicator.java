package com.gitforge.insights;

import com.gitforge.vcsapi.dto.IntegrityReport;

/**
 * A verification result reduced to one word, without losing what it said.
 *
 * <p><strong>Four states, not two.</strong> The tempting reduction is
 * healthy/unhealthy, and it is wrong in both directions: a repository whose scan
 * was truncated has not been shown to be healthy, and one where nothing was
 * verified has not been shown to be anything at all. Collapsing either into
 * "healthy" would be the product asserting something the scan never established.
 *
 * <p>The distinction already exists in {@link IntegrityReport}, whose
 * {@code healthy} is a nullable {@code Boolean} precisely so "not established"
 * can be told from "established false". This preserves it rather than flattening
 * it on the way out.
 */
public enum IntegrityIndicator {

    /** Every object in the scan was read back and matched its id, and the scan was complete. */
    HEALTHY,

    /** At least one object failed to match its id. Nothing else about the scan matters. */
    DAMAGED,

    /**
     * Nothing damaged was found, but the store holds more than one scan can read.
     *
     * <p>Deliberately not {@link #HEALTHY}: the objects beyond the ceiling were
     * never looked at, and a clean partial scan is not a clean repository.
     */
    TRUNCATED,

    /**
     * Nothing was verified at all — an empty repository, or a scan that examined
     * nothing.
     *
     * <p>Also deliberately not {@link #HEALTHY}. An empty repository has not been
     * shown to be sound; it has been shown to be empty.
     */
    NOT_VERIFIED;

    /**
     * Reads an indicator from a report.
     *
     * <p>Order matters. Damage wins over truncation, because a scan that found a
     * damaged object has established damage regardless of how much it did not
     * reach.
     */
    public static IntegrityIndicator of(IntegrityReport report) {
        if (report == null) {
            return NOT_VERIFIED;
        }
        if (!report.damaged().isEmpty() || Boolean.FALSE.equals(report.healthy())) {
            return DAMAGED;
        }
        if (report.truncated()) {
            return TRUNCATED;
        }
        if (report.healthy() == null || report.verified() == 0) {
            return NOT_VERIFIED;
        }
        return HEALTHY;
    }

    /** Whether this state is a positive claim that the store is sound. */
    public boolean soundnessEstablished() {
        return this == HEALTHY;
    }
}
