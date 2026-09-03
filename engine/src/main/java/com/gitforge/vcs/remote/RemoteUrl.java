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
            if (address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isAnyLocalAddress()
                    || address.isMulticastAddress()) {

                throw new RemoteException(
                        "Remote URL resolves to a non-public address (" + address.getHostAddress()
                                + "), which this server will not request: " + url);
            }
        }
    }
}
