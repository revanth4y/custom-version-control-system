package com.gitforge.vcsapi.dto;

import java.util.List;

/**
 * Objects handed to a peer that asked for them by id.
 *
 * <p>Only what was asked for, and only what is here. An id that names nothing is
 * simply absent from the answer rather than an error: the caller compares what it
 * asked for against what came back, which it must do anyway to know the transfer
 * is complete.
 *
 * @param objects the objects, canonical payload base64-encoded
 */
public record RemoteObjectsResponse(List<ObjectEntry> objects) {

    /**
     * One object on the wire.
     *
     * @param id its id, 40 hexadecimal characters
     * @param type blob, tree or commit
     * @param payload the canonical payload, base64-encoded, from which the
     *     receiver recomputes the id rather than taking it on trust
     */
    public record ObjectEntry(String id, String type, String payload) {
    }
}
