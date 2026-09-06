package com.gitforge.vcs.remote;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which addresses this server refuses to be pointed at.
 *
 * <p>Written after finding that {@code fd00::1} was accepted. The guard asked
 * {@link InetAddress} its four questions — loopback, link-local, site-local,
 * any-local — and treated that as the definition of private. It is not:
 * {@code isSiteLocalAddress} tests {@code fec0::/10}, which was deprecated in
 * 2004, while real private IPv6 networks use {@code fc00::/7}, for which the JDK
 * has no question at all. Every IPv6 private address passed.
 *
 * <p>So the cases below are mostly the ones a checklist would not produce,
 * because they are the ones that were actually wrong. Each was confirmed against
 * the running JDK before it was written: the ones under {@link Refused} were
 * accepted by the previous implementation, and the ones under {@link Allowed}
 * must keep being accepted, or the guard becomes a way to make the feature
 * unusable.
 *
 * <p>Literal addresses throughout, and no name that needs resolving. A test that
 * depends on DNS is a test that fails on a train.
 */
class RemoteUrlAddressRangeTest {

    private static InetAddress at(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException impossible) {
            throw new AssertionError("a literal address should never need a resolver", impossible);
        }
    }

    // ------------------------------------------------------------- refusals

    @Nested
    @DisplayName("addresses the server will not reach")
    class Refused {

        @ParameterizedTest(name = "{0}")
        @DisplayName("were already refused, and still are")
        @ValueSource(strings = {
                "127.0.0.1", "127.1.2.3",       // loopback
                "10.0.0.1", "172.16.0.1", "192.168.1.1", // private
                "169.254.169.254",              // the cloud metadata service
                "0.0.0.0",                      // unspecified
                "224.0.0.1",                    // multicast
                "::1", "fe80::1", "fec0::1",    // v6 loopback, link-local, old site-local
        })
        void alreadyRefused(String address) {
            assertThat(RemoteUrl.isForbidden(at(address))).isTrue();
        }

        @ParameterizedTest(name = "{0}")
        @DisplayName("were accepted before this change")
        @ValueSource(strings = {
                // The one that matters: where private IPv6 networks actually live.
                "fd00::1", "fdff:ffff::1", "fc00::1",
                // Carrier-grade NAT. A cloud instance's neighbours sit here.
                "100.64.0.1", "100.127.255.254",
                // "This network", and a bare 0 in a URL resolves into it.
                "0.0.0.1", "0.255.255.255",
                // Reserved for protocol assignments.
                "192.0.0.1", "192.0.0.255",
                // Reserved, and the broadcast address with it.
                "240.0.0.1", "255.255.255.255",
                // An IPv4 address wearing IPv6: NAT64, 6to4, IPv4-compatible.
                "64:ff9b::7f00:1",   // 127.0.0.1
                "64:ff9b::a00:1",    // 10.0.0.1
                "2002:7f00:1::",     // 127.0.0.1 via 6to4
                "2002:a9fe:a9fe::",  // 169.254.169.254 via 6to4
                "::7f00:1",          // 127.0.0.1, IPv4-compatible
        })
        void newlyRefused(String address) {
            assertThat(RemoteUrl.isForbidden(at(address))).isTrue();
        }

        @Test
        @DisplayName("and a URL naming one is refused, not merely the address")
        void refusedThroughTheUrl() {
            // The unit above proves the predicate. This proves the predicate is
            // reached: a rule nothing calls is not a rule.
            assertThatThrownBy(() -> RemoteUrl.validate("http://[fd00::1]/repo", false))
                    .isInstanceOf(RemoteException.class)
                    .hasMessageContaining("non-public");

            assertThatThrownBy(() -> RemoteUrl.validate("http://100.64.0.1/repo", false))
                    .isInstanceOf(RemoteException.class)
                    .hasMessageContaining("non-public");

            assertThatThrownBy(() -> RemoteUrl.validate("http://[64:ff9b::7f00:1]/repo", false))
                    .isInstanceOf(RemoteException.class)
                    .hasMessageContaining("non-public");
        }

        @Test
        @DisplayName("a decimal spelling of a loopback address is refused")
        void decimalSpelling() {
            // 2130706433 is 127.0.0.1. The resolver expands it, so this needed no
            // new code - it is here because it would be a real bypass if it ever
            // stopped being true, and nothing else would notice.
            assertThatThrownBy(() -> RemoteUrl.validate("http://2130706433/repo", false))
                    .isInstanceOf(RemoteException.class);
        }

        @Test
        @DisplayName("an IPv4-mapped IPv6 spelling of loopback is refused")
        void mappedSpelling() {
            assertThatThrownBy(() -> RemoteUrl.validate("http://[::ffff:127.0.0.1]/repo", false))
                    .isInstanceOf(RemoteException.class);
        }
    }

    // ------------------------------------------------------------- allowances

    @Nested
    @DisplayName("addresses the server may still reach")
    class Allowed {

        @ParameterizedTest(name = "{0}")
        @DisplayName("ordinary public addresses")
        @ValueSource(strings = {
                "8.8.8.8", "1.1.1.1", "93.184.216.34",
                "99.64.0.1",    // just below the carrier-NAT block
                "100.63.0.1", "100.128.0.1", // either side of it
                "193.0.0.1",    // not the reserved 192.0.0/24
                "192.1.0.1",    // nor is this
                "223.255.255.255", // just below multicast
                "2001:4860:4860::8888", "2606:4700::1111", // public IPv6
                "fb00::1",      // just below fc00::/7
                "fe00::1",      // just above it
        })
        void publicAddressesPass(String address) {
            assertThat(RemoteUrl.isForbidden(at(address))).isFalse();
        }

        @Test
        @DisplayName("a public URL is still accepted unchanged")
        void publicUrlPasses() {
            assertThat(RemoteUrl.validate("http://8.8.8.8/gitforge", false))
                    .isEqualTo("http://8.8.8.8/gitforge");
        }

        @Test
        @DisplayName("a private address is reachable when the deployment says so")
        void privateAllowedWhenPermitted() {
            // The escape hatch has to keep working, or an instance that genuinely
            // talks to a peer on its own network cannot.
            assertThat(RemoteUrl.validate("http://[fd00::1]/repo", true))
                    .isEqualTo("http://[fd00::1]/repo");
            assertThat(RemoteUrl.validate("http://127.0.0.1:8080/repo", true))
                    .isEqualTo("http://127.0.0.1:8080/repo");
        }
    }
}
