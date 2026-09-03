package com.gitforge.vcs.remote;

import com.gitforge.vcs.ref.RefException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Remote configuration on disk, and what a remote is allowed to point at.
 *
 * <p>The second half is the one that matters most. Registering a remote is the
 * first thing in this application that makes the <em>server</em> issue an
 * outbound request, so what it will and will not dial is a security property, not
 * a convenience.
 */
class RemoteConfigurationTest {

    @TempDir
    Path repositoryRoot;

    private RemoteStore store;

    @BeforeEach
    void setUp() {
        store = new RemoteStore(repositoryRoot);
    }

    @Nested
    @DisplayName("persistence")
    class Persistence {

        @Test
        void aRepositoryWithNoRemotesListsNone() {
            assertThat(store.list()).isEmpty();
            assertThat(store.get("origin")).isEmpty();
        }

        @Test
        void aRemoteSurvivesReopeningTheStore() {
            store.save(new Remote("origin", "https://peer.test/api/v1/repositories/octocat/demo"));

            RemoteStore reopened = new RemoteStore(repositoryRoot);

            assertThat(reopened.get("origin"))
                    .map(Remote::url)
                    .contains("https://peer.test/api/v1/repositories/octocat/demo");
        }

        @Test
        void savingTheSameNameRePointsRatherThanDuplicating() {
            store.save(new Remote("origin", "https://one.test/a"));
            store.save(new Remote("origin", "https://two.test/b"));

            assertThat(store.list()).hasSize(1);
            assertThat(store.get("origin")).map(Remote::url).contains("https://two.test/b");
        }

        @Test
        void remotesAreListedByName() {
            store.save(new Remote("upstream", "https://one.test/a"));
            store.save(new Remote("origin", "https://two.test/b"));
            store.save(new Remote("backup", "https://three.test/c"));

            assertThat(store.list()).extracting(Remote::name)
                    .containsExactly("backup", "origin", "upstream");
        }

        @Test
        void deletingRemovesOnlyThatOne() {
            store.save(new Remote("origin", "https://one.test/a"));
            store.save(new Remote("backup", "https://two.test/b"));

            assertThat(store.delete("origin")).isTrue();

            assertThat(store.list()).extracting(Remote::name).containsExactly("backup");
        }

        @Test
        void deletingSomethingAbsentIsFalseRatherThanAnError() {
            assertThat(store.delete("origin")).isFalse();
        }

        @Test
        void theFileSitsBesideHead() {
            store.save(new Remote("origin", "https://one.test/a"));

            assertThat(Files.isRegularFile(repositoryRoot.resolve("REMOTES"))).isTrue();
        }

        @Test
        void aUrlContainingSpacesIsPreservedExactly() {
            // The separator is a tab, which a remote name cannot contain, so the
            // rest of the line is the URL whatever it holds.
            store.save(new Remote("origin", "https://peer.test/a%20b/c"));

            assertThat(store.get("origin")).map(Remote::url).contains("https://peer.test/a%20b/c");
        }

        @Test
        void aMalformedLineIsReportedRatherThanSilentlyDropped() throws IOException {
            Files.writeString(
                    repositoryRoot.resolve("REMOTES"), "no-separator-here\n", StandardCharsets.UTF_8);

            assertThatThrownBy(() -> store.list())
                    .isInstanceOf(RemoteException.class)
                    .hasMessageContaining("Malformed remote entry");
        }

        @Test
        void tooManyRemotesIsRefused() {
            for (int index = 0; index < 32; index++) {
                store.save(new Remote("remote" + index, "https://peer.test/" + index));
            }

            assertThatThrownBy(() -> store.save(new Remote("overflow", "https://peer.test/x")))
                    .isInstanceOf(RemoteException.class)
                    .hasMessageContaining("at most");
        }

        @Test
        void aRemoteNameIsValidatedOnTheWayIn() {
            assertThatThrownBy(() -> store.save(new Remote("bad/name", "https://peer.test/a")))
                    .isInstanceOf(RefException.class);
            assertThatThrownBy(() -> store.save(new Remote("..", "https://peer.test/a")))
                    .isInstanceOf(RefException.class);
        }
    }

    @Nested
    @DisplayName("what the server will dial")
    class Ssrf {

        @Test
        void anOrdinaryPublicHttpsUrlIsAccepted() {
            // Resolution is not attempted for a name that is already an address,
            // and 93.184.216.34 is a documentation address outside every private
            // range - so this exercises the check rather than the network.
            assertThat(RemoteUrl.validate("https://93.184.216.34/api", false))
                    .isEqualTo("https://93.184.216.34/api");
        }

        @Test
        void loopbackIsRefusedByDefault() {
            assertThatThrownBy(() -> RemoteUrl.validate("http://127.0.0.1:8080/api", false))
                    .isInstanceOf(RemoteException.class)
                    .hasMessageContaining("non-public address");
        }

        @Test
        void privateRangesAreRefusedByDefault() {
            for (String url : new String[] {
                    "http://10.0.0.5/api", "http://192.168.1.1/api", "http://172.16.0.1/api"}) {

                assertThatThrownBy(() -> RemoteUrl.validate(url, false))
                        .isInstanceOf(RemoteException.class)
                        .hasMessageContaining("non-public address");
            }
        }

        @Test
        void linkLocalIsRefusedByDefault() {
            // The address cloud metadata services sit behind.
            assertThatThrownBy(() -> RemoteUrl.validate("http://169.254.169.254/latest/meta-data", false))
                    .isInstanceOf(RemoteException.class)
                    .hasMessageContaining("non-public address");
        }

        @Test
        void privateAddressesAreAllowedOnlyWhenExplicitlyPermitted() {
            assertThat(RemoteUrl.validate("http://127.0.0.1:8080/api", true))
                    .isEqualTo("http://127.0.0.1:8080/api");
        }

        @Test
        void otherSchemesAreRefused() {
            for (String url : new String[] {
                    "file:///etc/passwd", "ftp://peer.test/a", "gopher://peer.test/a",
                    "jar:file:///a!/b"}) {

                assertThatThrownBy(() -> RemoteUrl.validate(url, true))
                        .isInstanceOf(RemoteException.class);
            }
        }

        @Test
        void credentialsInTheUrlAreRefused() {
            assertThatThrownBy(() ->
                    RemoteUrl.validate("https://user:secret@93.184.216.34/api", true))
                    .isInstanceOf(RemoteException.class)
                    .hasMessageContaining("credentials");
        }

        @Test
        void aRelativeOrHostlessUrlIsRefused() {
            assertThatThrownBy(() -> RemoteUrl.validate("/api/v1/repositories", true))
                    .isInstanceOf(RemoteException.class);
            assertThatThrownBy(() -> RemoteUrl.validate("http:///api", true))
                    .isInstanceOf(RemoteException.class);
        }

        @Test
        void anEmptyOrOverlongUrlIsRefused() {
            assertThatThrownBy(() -> RemoteUrl.validate("", true))
                    .isInstanceOf(RemoteException.class);
            assertThatThrownBy(() ->
                    RemoteUrl.validate("https://peer.test/" + "a".repeat(RemoteUrl.MAX_LENGTH), true))
                    .isInstanceOf(RemoteException.class)
                    .hasMessageContaining("at most");
        }
    }
}
