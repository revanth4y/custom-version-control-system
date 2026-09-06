package com.gitforge.cli.security;

import com.gitforge.cli.CliException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A file only its owner can read.
 *
 * <p>This exists because the previous attempt at it did nothing on Windows. The
 * credentials file was made {@code rw-------} through
 * {@code setPosixFilePermissions}, and on a filesystem with no POSIX bits that
 * call throws {@link UnsupportedOperationException} — which was caught and
 * ignored. The comment said Windows "has no POSIX bits", which is true, and then
 * drew the wrong conclusion: that there was nothing to do. Windows expresses the
 * same idea through an access control list, and the file was being left with
 * whatever it inherited from its parent directory.
 *
 * <p>What that inherited was measured rather than assumed. A freshly written
 * credentials file on Windows 11 carried four entries — the owner,
 * {@code NT AUTHORITY\SYSTEM}, {@code BUILTIN\Administrators}, and a fourth
 * account with {@code READ_DATA}. The token was readable by principals nobody
 * chose, and {@code auth status} reported the permissions as
 * {@code "unavailable"}, which reads like an absent feature rather than an
 * absent protection.
 *
 * <p><strong>One meaning, two mechanisms.</strong> POSIX gets {@code rw-------}.
 * An ACL filesystem gets a list containing exactly one entry: allow the owner.
 * Setting an explicit list also detaches the file from its parent's inheritance,
 * so the entries above are removed rather than merely joined by a stricter one —
 * that was confirmed against the real filesystem, and again with {@code icacls},
 * which afterwards reports a single non-inherited ace.
 *
 * <p>Administrators and SYSTEM are removed along with the rest. They lose
 * nothing they could not take back — an administrator may seize ownership and
 * rewrite the list — which is the same standing {@code root} has against
 * {@code rw-------}. Leaving them in would make the two platforms mean different
 * things while looking equally locked, and the point of this class is that they
 * mean the same thing.
 *
 * <p><strong>It fails closed.</strong> If the file cannot be protected, the
 * caller is told and nothing is written. A token in a file that anyone can read
 * is worse than a token that could not be saved, because the first looks like it
 * worked. Refusing is also the only honest answer available: there is no third
 * option where the token is stored and the user is meaningfully warned, since
 * the warning scrolls away and the file remains.
 *
 * <p><strong>Nothing here reads or reports file contents.</strong> Every failure
 * names the path and the reason. A message that quoted the file would put the
 * token in a terminal, a log and a bug report at once.
 */
public final class OwnerOnlyFile {

    /** POSIX: the owner may read and write; nobody else has anything. */
    private static final String POSIX_OWNER_ONLY = "rw-------";

    private OwnerOnlyFile() {
    }

    /**
     * Ensures the file exists and only its owner can read it.
     *
     * <p>Order matters. The file is created empty and restricted <em>before</em>
     * the caller writes anything into it, so a secret is never briefly present in
     * a file the default permissions left open. Doing it the other way round —
     * write, then restrict — leaves a window whose length is decided by the
     * scheduler, and a window like that is one an attacker on the same machine
     * can widen by loading it.
     *
     * @throws CliException if the file cannot be created or cannot be protected
     */
    public static void createProtected(Path file) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!Files.exists(file)) {
                Files.createFile(file);
            }
        } catch (IOException cannotCreate) {
            throw CliException.failure(
                    "Could not create " + file + ": " + cannotCreate.getMessage());
        }
        restrict(file);
    }

    /**
     * Narrows an existing file to its owner, and proves it worked.
     *
     * <p>The result is read back rather than inferred from the call returning.
     * A filesystem that accepts the request and stores something else — a network
     * share mapping principals its own way, a driver that treats the list as
     * advisory — would otherwise leave this class reporting a protection it never
     * established, which is the exact failure it was written to remove.
     *
     * @throws CliException if the filesystem cannot express owner-only access, or
     *     if it accepted the change and did not apply it
     */
    public static void restrict(Path file) {
        if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
            restrictPosix(file);
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl != null) {
            restrictAcl(file, acl);
            return;
        }
        throw cannotProtect(file,
                "this filesystem supports neither POSIX permissions nor access control lists");
    }

    private static void restrictPosix(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(POSIX_OWNER_ONLY));

            Set<java.nio.file.attribute.PosixFilePermission> applied =
                    Files.getPosixFilePermissions(file);
            String actual = PosixFilePermissions.toString(applied);
            if (!POSIX_OWNER_ONLY.equals(actual)) {
                throw cannotProtect(file,
                        "the filesystem kept " + actual + " instead of " + POSIX_OWNER_ONLY);
            }
        } catch (IOException | UnsupportedOperationException refused) {
            throw cannotProtect(file, refused.getMessage());
        }
    }

    /**
     * Replaces the access control list with a single entry for the owner.
     *
     * <p>The owner is taken from the file rather than from the running process.
     * They are normally the same, and where they are not the process would not be
     * permitted to rewrite the list anyway — granting the list to whoever happens
     * to be running would then either fail loudly or, worse, hand access to an
     * account that is not the one the file belongs to.
     *
     * <p>Every permission is granted to that one principal. The list is about
     * <em>who</em>, not about how much they may do: the owner of their own
     * credentials file needs to read it, rewrite it and delete it, and enumerating
     * a narrower set would only invite a later operation to fail for a reason
     * unrelated to security.
     */
    private static void restrictAcl(Path file, AclFileAttributeView view) {
        try {
            UserPrincipal owner = view.getOwner();
            if (owner == null) {
                throw cannotProtect(file, "the filesystem reports no owner for it");
            }
            AclEntry ownerOnly = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build();

            view.setAcl(List.of(ownerOnly));
            requireOwnerOnly(file, view.getAcl(), owner);
        } catch (IOException | SecurityException | UnsupportedOperationException refused) {
            throw cannotProtect(file, refused.getMessage());
        }
    }

    /**
     * Checks that nobody but the owner is named, and that nobody is denied.
     *
     * <p>A DENY entry for the owner would leave the file locked against the person
     * it belongs to, which is a broken file rather than a protected one — so it is
     * refused here rather than discovered on the next read.
     */
    private static void requireOwnerOnly(Path file, List<AclEntry> entries, UserPrincipal owner) {
        Set<String> others = new LinkedHashSet<>();
        for (AclEntry entry : entries) {
            if (!entry.principal().getName().equals(owner.getName())) {
                others.add(entry.principal().getName());
            } else if (entry.type() == AclEntryType.DENY) {
                throw cannotProtect(file, "the filesystem denied the owner access to their own file");
            }
        }
        if (!others.isEmpty()) {
            throw cannotProtect(file, "the filesystem kept access for " + String.join(", ", others));
        }
    }

    /**
     * How the file is actually protected, for {@code auth status} to report.
     *
     * <p>Describes what is there rather than what was intended. The old version
     * answered {@code "unavailable"} on every non-POSIX filesystem, which is what
     * a Windows user saw next to a file that was in fact readable by four
     * principals — a true statement about POSIX bits standing in for a false
     * impression about safety.
     */
    public static String describe(Path file) {
        if (!Files.exists(file)) {
            return "absent";
        }
        try {
            PosixFileAttributeView posix =
                    Files.getFileAttributeView(file, PosixFileAttributeView.class);
            if (posix != null) {
                return PosixFilePermissions.toString(Files.getPosixFilePermissions(file));
            }
            AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
            if (acl != null) {
                UserPrincipal owner = acl.getOwner();
                long principals = acl.getAcl().stream()
                        .map(entry -> entry.principal().getName())
                        .distinct()
                        .count();
                return principals == 1 && owner != null
                        ? "owner-only (acl: " + owner.getName() + ")"
                        : "acl: " + principals + " principals";
            }
            return "unprotected (no permission model)";
        } catch (IOException | UnsupportedOperationException unreadable) {
            return "unknown";
        }
    }

    /** Whether the file is currently restricted to one principal. */
    public static boolean isOwnerOnly(Path file) {
        if (!Files.exists(file)) {
            return false;
        }
        try {
            PosixFileAttributeView posix =
                    Files.getFileAttributeView(file, PosixFileAttributeView.class);
            if (posix != null) {
                return Files.getPosixFilePermissions(file).stream()
                        .allMatch(permission -> permission.name().startsWith("OWNER_"));
            }
            AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
            if (acl != null) {
                UserPrincipal owner = acl.getOwner();
                return owner != null && acl.getAcl().stream()
                        .allMatch(entry -> entry.principal().getName().equals(owner.getName()));
            }
            return false;
        } catch (IOException | UnsupportedOperationException unreadable) {
            return false;
        }
    }

    private static CliException cannotProtect(Path file, String because) {
        // Names the file and the reason, never anything the file contains.
        return CliException.failure(
                "Refusing to store credentials in " + file
                        + " because it cannot be made readable only by you: " + because
                        + ". Move GITFORGE_HOME to a filesystem that supports file permissions.");
    }
}
