package com.gitforge.vcs.remote;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * What a remote is allowed to point at.
 *
 * <p>Registering a remote is the first thing in GitForge that makes <em>the
 * server</em> issue an outbound request on a caller's behalf. Until now nothing
 * in {@code server/src/main/java} made any outbound call at all, so this is a new
 * class of exposure rather than an extension of an existing one: without a guard,
 * a URL is a way to ask the server to reach whatever it can reach and report what
 * came back.
 *
 * <p>The guard is deliberately modest and deliberately explicit:
 *
 * <ul>
 *   <li>only {@code http} and {@code https};
 *   <li>no credentials in the URL — a password in a stored remote is a password
 *       in a file nobody remembers writing;
 *   <li>a length ceiling, so a stored remote cannot itself be the payload;
 *   <li>and <strong>every address the host resolves to</strong> must be a public
 *       one, unless private addresses are explicitly permitted.
 * </ul>
 *
 * <p>Checking every resolved address rather than the first matters: a name with
 * one public and one loopback address would otherwise pass on whichever the
 * resolver happened to return first.
 *
 * <p><strong>What counts as private is decided here, not by the JDK.</strong>
 * The first version of this asked {@link InetAddress} the four questions it
 * answers - loopback, link-local, site-local, any-local - and treated the set as
 * complete. It is not, and the gap was measured rather than suspected:
 * {@code fd00::1} passed every one of them. {@code isSiteLocalAddress} tests
 * {@code fec0::/10}, the site-local range deprecated in 2004; the range real
 * private IPv6 networks actually use is {@code fc00::/7}, and nothing in the JDK
 * has a question for it. IPv6 private networks were therefore entirely
 * unguarded. {@link #isForbidden} names every range explicitly instead, so what
 * is refused is visible in one place and does not depend on what the platform
 * happens to have a method for.
 *
 * <p>Alternate spellings were measured too, and most need no special handling:
 * {@code http://2130706433/} resolves to {@code 127.0.0.1} and is refused,
 * {@code ::ffff:127.0.0.1} is handed back as an {@code Inet4Address} and is
 * refused, hexadecimal and octal forms do not resolve at all. The ones that do
 * need handling are the IPv6 formats that carry an IPv4 address inside them -
 * NAT64, 6to4, IPv4-compatible - where the bytes that decide reachability are
 * not the bytes the range checks would look at.
 *
 * <p><strong>What this does not do.</strong> It cannot close DNS rebinding — the
 * name is resolved here and again by the HTTP client, and nothing guarantees the
 * two answers agree. Closing that needs the connection itself to be pinned to a
 * vetted address, which belongs with the deeper transport hardening rather than
 * here. This is the minimum that makes the exposure bounded and visible, not a
 * claim that outbound requests are safe against a determined attacker.
 */
public final class RemoteUrl {

    /** Long enough for any real address, short enough not to be a payload itself. */
    static final int MAX_LENGTH = 2048;

    private RemoteUrl() {
    }

    /**
     * Returns {@code url} unchanged if a remote may point at it.
     *
     * @param allowPrivateAddresses whether hosts resolving to loopback, link-local
     *     or site-local addresses are permitted. False in normal operation; true
     *     only where the deployment genuinely talks to a peer on the same host or
     *     private network, which is a decision for whoever runs it rather than a
     *     default worth assuming
     * @throws RemoteException if the URL is malformed, uses another scheme,
     *     carries credentials, is too long, or resolves somewhere it may not reach
     */
    public static String validate(String url, boolean allowPrivateAddresses) {
        if (url == null || url.isBlank()) {
            throw new RemoteException("Remote URL must not be empty");
        }
        if (url.length() > MAX_LENGTH) {
            throw new RemoteException("Remote URL must be at most " + MAX_LENGTH + " characters");
        }

        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException ex) {
            throw new RemoteException("Remote URL is not a valid URI: " + url, ex);
        }
        if (!uri.isAbsolute() || uri.getScheme() == null) {
            throw new RemoteException("Remote URL must be absolute: " + url);
        }

        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new RemoteException("Remote URL must use http or https, not " + scheme);
        }
        if (uri.getUserInfo() != null) {
            throw new RemoteException("Remote URL must not carry credentials");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new RemoteException("Remote URL must name a host: " + url);
        }

        if (!allowPrivateAddresses) {
            requirePublic(host, url);
        }
        return url.trim();
    }

    /**
     * Refuses a host any of whose addresses is one the server should not be
     * persuaded to reach.
     *
     * <p>A name that cannot be resolved is refused rather than allowed. An
     * unresolvable remote is useless anyway, and treating "unknown" as "probably
     * fine" is how a guard becomes decorative.
     */
    private static void requirePublic(String host, String url) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException ex) {
            throw new RemoteException("Remote URL host could not be resolved: " + host, ex);
        }
        for (InetAddress address : addresses) {
            if (isForbidden(address)) {
                throw new RemoteException(
                        "Remote URL resolves to a non-public address (" + address.getHostAddress()
                                + "), which this server will not request: " + url);
            }
        }
    }

    /**
     * Whether the server refuses to be pointed at this address.
     *
     * <p>Ranges are named rather than delegated, for the reason the class comment
     * gives. Each one below is here because reaching it means reaching something
     * on this host or this network, not because a list somewhere recommends it.
     */
    static boolean isForbidden(InetAddress address) {
        if (address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] octets = address.getAddress();
        return octets.length == 4 ? isForbiddenV4(octets) : isForbiddenV6(octets);
    }

    /**
     * IPv4 ranges the JDK's four questions do not cover.
     *
     * <p>{@code 100.64/10} is carrier-grade NAT, which is a private network in
     * every sense that matters here and is what a cloud instance's neighbours sit
     * on. {@code 0/8} means "this network" and a bare {@code 0} in a URL resolves
     * into it. {@code 192.0.0/24} is reserved for protocol assignments. Everything
     * from {@code 240/4} up is reserved and includes the broadcast address, so it
     * is refused as a block rather than one address at a time.
     */
    private static boolean isForbiddenV4(byte[] octets) {
        int first = octets[0] & 0xFF;
        int second = octets[1] & 0xFF;
        int third = octets[2] & 0xFF;

        return first == 0
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 192 && second == 0 && third == 0)
                || first >= 240;
    }

    /**
     * IPv6 ranges, including the ones that carry an IPv4 address inside them.
     *
     * <p>{@code fc00::/7} is the gap this method exists for: unique-local
     * addresses, which is where private IPv6 networks actually are.
     *
     * <p>The rest are IPv4 addresses in IPv6 clothing. An embedded address is
     * extracted and asked the IPv4 questions, because {@code 64:ff9b::7f00:1} is
     * not loopback by any IPv6 test and is {@code 127.0.0.1} by the only test that
     * decides where a packet goes. 6to4 is included even though it is deprecated
     * and unlikely to be routed: it costs four lines, and "unlikely to work" is
     * not the standard this class is held to.
     */
    private static boolean isForbiddenV6(byte[] octets) {
        // fc00::/7 - unique local. The first seven bits are what defines it.
        if ((octets[0] & 0xFE) == 0xFC) {
            return true;
        }
        // 64:ff9b::/96 - the well-known NAT64 prefix, IPv4 in the last four bytes.
        if (octets[0] == 0x00 && octets[1] == 0x64
                && (octets[2] & 0xFF) == 0xFF && (octets[3] & 0xFF) == 0x9B
                && allZero(octets, 4, 12)) {
            return embeddedIsForbidden(octets, 12);
        }
        // 2002::/16 - 6to4, IPv4 in bytes two to five.
        if ((octets[0] & 0xFF) == 0x20 && (octets[1] & 0xFF) == 0x02) {
            return embeddedIsForbidden(octets, 2);
        }
        // ::/96 - IPv4-compatible. Excludes :: and ::1, which the questions above
        // already answered, so what is left here genuinely carries an address.
        if (allZero(octets, 0, 12)) {
            return embeddedIsForbidden(octets, 12);
        }
        return false;
    }

    /** The four bytes at {@code offset}, judged as the IPv4 address they are. */
    private static boolean embeddedIsForbidden(byte[] octets, int offset) {
        byte[] four = new byte[]{
                octets[offset], octets[offset + 1], octets[offset + 2], octets[offset + 3]};
        try {
            return isForbidden(InetAddress.getByAddress(four));
        } catch (java.net.UnknownHostException impossible) {
            // getByAddress only rejects a wrong length, and this is always four.
            // Refusing is the answer that cannot be wrong if that ever changes.
            return true;
        }
    }

    private static boolean allZero(byte[] octets, int from, int to) {
        for (int i = from; i < to; i++) {
            if (octets[i] != 0) {
                return false;
            }
        }
        return true;
    }
}
