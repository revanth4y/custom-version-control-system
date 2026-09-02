package com.gitforge.vcs.remote;

/**
 * How much one transfer may move, and how hard it may work to do it.
 *
 * <p>One home for every bound, for the reason {@code ContentLimits} gives about
 * file sizes: a limit enforced in two places is a limit that will eventually
 * disagree with itself. These are the numbers a peer can make this server spend,
 * so they are stated rather than left to whatever the request happens to contain.
 *
 * <p>The figures are derived from what this project actually holds. Across the
 * ten repositories in the running deployment the entire object store is 48,071
 * bytes, the largest single repository being 16,939 bytes across 240 objects — so
 * these ceilings are orders of magnitude above real use, and exist to bound the
 * pathological case rather than to shape the normal one.
 */
public final class TransferLimits {

    /**
     * Objects in one request, in either direction.
     *
     * <p>The same order as {@code CommitApiService.MAX_CHANGES} (500), which
     * bounds the other place a single request can ask the engine to write many
     * objects at once.
     */
    public static final int MAX_OBJECTS_PER_BATCH = 500;

    /**
     * Total payload bytes in one batch.
     *
     * <p>Below {@code RequestSizeLimitFilter.MAX_REQUEST_BYTES} (16 MiB) with room
     * for base64's third and the surrounding JSON, so a batch this layer accepts
     * is one the transport will actually carry. Accepting more here would mean
     * refusing at a lower layer with a less useful message.
     */
    public static final long MAX_BATCH_BYTES = 8L * 1024 * 1024;

    /**
     * Ids named in one read request.
     *
     * <p>Reading objects and asking which are missing are both <em>reads</em>, and
     * every read in this API is a GET that an anonymous caller may make against a
     * public repository. Keeping them GETs means the security configuration needs
     * no new rule — the existing "GET under /repositories is public, everything
     * else is authenticated" holds unchanged, with visibility still enforced in
     * the service layer.
     *
     * <p>The cost is that ids travel in the query string, so the count has to keep
     * the URL short. Thirty-two ids is about 1.3 kB of query, comfortably inside
     * what every server and proxy accepts, and for the largest repository here —
     * 240 objects — a whole fetch is a handful of round trips.
     */
    public static final int MAX_IDS_PER_REQUEST = 32;

    /**
     * Objects one whole fetch or push may move.
     *
     * <p>A fetch walks in rounds, each bounded by the batch size; without a
     * ceiling on the walk itself a peer could keep answering forever. Matches the
     * sweep ceiling {@code GarbageCollector.DEFAULT_MAX_SWEPT_OBJECTS} uses, so a
     * repository this server will collect is one it will also transfer.
     */
    public static final int MAX_OBJECTS_PER_TRANSFER = 10_000;

    /**
     * Rounds of asking a peer for more objects before giving up.
     *
     * <p>Each round strictly shrinks what is still missing — an object arrives or
     * the transfer fails — so this cannot be reached by a well-behaved peer. It
     * exists so a misbehaving one cannot hold the walk open indefinitely.
     */
    public static final int MAX_TRANSFER_ROUNDS = 256;

    private TransferLimits() {
    }
}
