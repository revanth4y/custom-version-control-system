package com.gitforge.cli.security;

import com.gitforge.cli.CliException;
import com.gitforge.cli.config.Credentials;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Whether the credentials file is actually protected, on the filesystem that is
 * actually there.
 *
 * <p>The protection this replaces was a call to {@code setPosixFilePermissions}
 * whose {@link UnsupportedOperationException} was caught and discarded. On Linux
 * it worked; on Windows it did nothing at all, and the status command reported
 * {@code "unavailable"}, which reads as a missing feature rather than a missing
 * protection. A measurement on Windows 11 found the token readable by four
 * principals: the owner, {@code SYSTEM}, {@code Administrators}, and a fourth
 * account inherited from the parent directory.
 *
 * <p>These tests therefore ask the filesystem what it did, not the code what it
 * meant. Each runs whichever question its platform can answer — POSIX bits where
 * there are POSIX bits, the access control list where there is one — and the
 * cases that need neither run everywhere. Nothing is skipped silently: the
 * platform decides which assertion is meaningful, and every platform has one.
 */
class OwnerOnlyFileTest {

    @TempDir
    Path home;

    private static boolean isPosix(Path path) {
        return Files.getFileAttributeView(path, PosixFileAttributeView.class) != null;
    }

    /**
     * The single question that means the same thing on both platforms.
     *
     * <p>Named principals on an ACL filesystem, group and other bits on a POSIX
     * one. Asserting through this rather than through either mechanism keeps the
     * test honest about what it is claiming: not "the call was made", but "nobody
     * else can read it".
     */
    private static void assertOnlyOwnerCanRead(Path file) throws IOException {
        if (isPosix(file)) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
            assertThat(permissions)
                    .as("no group or other access")
                    .allSatisfy(permission ->
                            assertThat(permission.name()).startsWith("OWNER_"));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        assertThat(acl).as("a filesystem with neither model would fail closed instead").isNotNull();

        String owner = acl.getOwner().getName();
        assertThat(acl.getAcl())
                .as("every entry names the owner and nobody else")
                .allSatisfy(entry ->
                        assertThat(entry.principal().getName()).isEqualTo(owner));
    }

    // -------------------------------------------------------- the file itself

    @Nested
    @DisplayName("a file created through OwnerOnlyFile")
    class Created {

        @Test
        @DisplayName("is readable by nobody but its owner")
        void ownerOnly() throws IOException {
            Path file = home.resolve("credentials");

            OwnerOnlyFile.createProtected(file);

            assertThat(Files.exists(file)).isTrue();
            assertOnlyOwnerCanRead(file);
            assertThat(OwnerOnlyFile.isOwnerOnly(file)).isTrue();
        }

        @Test
        @DisplayName("is protected before it has anything in it")
        void protectedBeforeWritten() throws IOException {
            // The ordering is the point. A file created open and narrowed after
            // the token is in it is readable for the gap between the two calls,
            // and that gap is decided by the scheduler rather than by us.
            Path file = home.resolve("credentials");

            OwnerOnlyFile.createProtected(file);

            assertThat(Files.size(file)).as("nothing written yet").isZero();
            assertOnlyOwnerCanRead(file);
        }

        @Test
        @DisplayName("keeps its protection when it is rewritten")
        void survivesRewrite() throws IOException {
            Path file = home.resolve("credentials");
            OwnerOnlyFile.createProtected(file);

            Files.writeString(file, "host\ttoken\n");
            OwnerOnlyFile.restrict(file);

            assertOnlyOwnerCanRead(file);
        }

        @Test
        @DisplayName("creates the directory it needs")
        void createsParent() throws IOException {
            Path file = home.resolve("nested/deeper/credentials");

            OwnerOnlyFile.createProtected(file);

            assertThat(Files.isRegularFile(file)).isTrue();
            assertOnlyOwnerCanRead(file);
        }
    }

    // --------------------------------------------------------- failure paths

    @Nested
    @DisplayName("when it cannot be protected")
    class FailsClosed {

        @Test
        @DisplayName("restricting something that is not there is refused")
        void absentFile() {
            // Fail closed rather than report success about a file that does not
            // exist: a caller about to write a token needs to hear "no".
            assertThatThrownBy(() -> OwnerOnlyFile.restrict(home.resolve("never-created")))
                    .isInstanceOf(CliException.class);
        }

        @Test
        @DisplayName("the refusal never quotes what the file contains")
        void neverLeaksContents() throws IOException {
            Path file = home.resolve("credentials");
            Files.writeString(file, "gitforge.example\ttop-secret-token-value\n");

            String message;
            try {
                OwnerOnlyFile.restrict(home.resolve("no-such-file"));
                message = "";
            } catch (CliException refused) {
                message = String.valueOf(refused.getMessage());
            }

            assertThat(message).doesNotContain("top-secret-token-value");
        }

        @Test
        @DisplayName("a file that cannot be created is refused, not silently skipped")
        void unwritableParent() throws IOException {
            // A regular file where a directory would have to be. Every filesystem
            // refuses this, which is what makes it a portable way to fail.
            Path blocker = home.resolve("blocker");
            Files.writeString(blocker, "not a directory");

            assertThatThrownBy(() -> OwnerOnlyFile.createProtected(blocker.resolve("credentials")))
                    .isInstanceOf(CliException.class);
        }
    }

    // -------------------------------------------------------------- reporting

    @Nested
    @DisplayName("what auth status is told")
    class Reporting {

        @Test
        @DisplayName("says how the file is protected, on every platform")
        void describesTruthfully() {
            Path file = home.resolve("credentials");
            OwnerOnlyFile.createProtected(file);

            String described = OwnerOnlyFile.describe(file);

            // The old answer on Windows. It was true about POSIX and misleading
            // about safety, which is the combination worth never printing again.
            assertThat(described).isNotEqualTo("unavailable");
            assertThat(described).isNotBlank();
            if (isPosix(file)) {
                assertThat(described).isEqualTo("rw-------");
            } else {
                assertThat(described).startsWith("owner-only");
            }
        }

        @Test
        @DisplayName("says so plainly when there is no file")
        void describesAbsence() {
            assertThat(OwnerOnlyFile.describe(home.resolve("nothing"))).isEqualTo("absent");
        }
    }

    // ------------------------------------------------- and through Credentials

    @Nested
    @DisplayName("the credentials file the CLI actually writes")
    class ThroughCredentials {

        @Test
        @DisplayName("is owner-only the moment a token is stored")
        void storedTokenIsProtected() throws IOException {
            Path file = home.resolve("credentials");
            Credentials credentials = new Credentials(file, new Redactor());

            credentials.store("gitforge.example", "a-token-worth-protecting");

            assertOnlyOwnerCanRead(file);
            assertThat(credentials.tokenFor("gitforge.example"))
                    .as("and is still usable by the person it belongs to")
                    .contains("a-token-worth-protecting");
        }

        @Test
        @DisplayName("stays owner-only after a second token is added")
        void secondWriteKeepsProtection() throws IOException {
            Path file = home.resolve("credentials");
            Credentials credentials = new Credentials(file, new Redactor());

            credentials.store("one.example", "first-token-value");
            credentials.store("two.example", "second-token-value");

            assertOnlyOwnerCanRead(file);
            assertThat(credentials.hosts()).containsExactly("one.example", "two.example");
        }

        @Test
        @DisplayName("reports its protection rather than 'unavailable'")
        void statusIsHonest() {
            Path file = home.resolve("credentials");
            Credentials credentials = new Credentials(file, new Redactor());
            credentials.store("gitforge.example", "a-token-worth-protecting");

            assertThat(credentials.permissions()).isNotEqualTo("unavailable");
            assertThat(String.valueOf(credentials.describe().get("permissions")))
                    .isNotEqualTo("unavailable");
        }

        @Test
        @DisplayName("never has the token in it while it is readable by others")
        void neverBrieflyExposed() throws IOException {
            // Checked by content rather than by timing, which cannot be observed
            // reliably: if the file has a token in it, it must already be narrow.
            Path file = home.resolve("credentials");
            new Credentials(file, new Redactor()).store("gitforge.example", "a-token-worth-protecting");

            assertThat(Files.readString(file)).contains("a-token-worth-protecting");
            assertOnlyOwnerCanRead(file);
        }
    }

    // ------------------------------------------- what the ACL path really did

    @Nested
    @DisplayName("on a filesystem with access control lists")
    class AclSpecific {

        @Test
        @DisplayName("the inherited entries are removed, not merely joined")
        void inheritanceIsBroken() throws IOException {
            Path file = home.resolve("credentials");
            Files.createFile(file);

            AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
            if (acl == null) {
                // POSIX machine. The equivalent claim is made above, through the
                // permission bits, so nothing is left unproven here.
                return;
            }

            int before = (int) acl.getAcl().stream()
                    .map(entry -> entry.principal().getName())
                    .distinct()
                    .count();

            OwnerOnlyFile.restrict(file);

            java.util.List<AclEntry> after = acl.getAcl();
            assertThat(after)
                    .as("one principal, however many were inherited (there were " + before + ")")
                    .extracting(entry -> entry.principal().getName())
                    .containsOnly(acl.getOwner().getName());
            assertThat(after).allSatisfy(entry ->
                    assertThat(entry.type()).isEqualTo(java.nio.file.attribute.AclEntryType.ALLOW));
        }
    }
}
