package com.gitforge.cli.security;

import com.gitforge.cli.CliException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Where a path ends up, when a link says one thing and the filesystem says
 * another.
 *
 * <p>The escape this exists for: a symbolic link inside the sandbox pointing at
 * a name outside it that does not exist yet. A link like that <em>exists</em> as
 * far as NOFOLLOW_LINKS is concerned, so the search for the nearest existing
 * ancestor stopped at the link; and toRealPath on it throws, because its target
 * is missing, so the old fallback answered with the link's own path - which is
 * inside the sandbox. The path was accepted, and writing through it created the
 * file outside. A path that does not exist yet is usually one about to be
 * created, which is what made this worth fixing rather than noting.
 *
 * <p>So the cases below are not only about refusing the obvious link out. They
 * pin the shape of the bug: dangling rather than live, the child of a dangling
 * link, a chain, and - the one that matters - that a write through the returned
 * path leaves nothing outside the sandbox. The last of those checks the security
 * property rather than an intermediate value, because the intermediate value was
 * exactly what used to look fine.
 *
 * <p><strong>On platforms.</strong> These need real symbolic links, and Windows
 * grants that only with Developer Mode or the privilege. Each case that needs
 * one tries to create it and, failing that, stops itself with a stated reason,
 * so a machine that cannot make links reports why rather than quietly reporting
 * nothing. The cases that need no link - traversal, prefix collision, an
 * ordinary path that does not exist yet - run everywhere, on every platform.
 */
class SandboxSymlinkContainmentTest {

    @TempDir
    Path base;

    private Path root;
    private Path outside;
    private SandboxPath sandbox;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectories(base.resolve("sandbox"));
        outside = Files.createDirectories(base.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.createDirectories(root.resolve("inside/deep"));
        Files.writeString(root.resolve("inside/deep/file.txt"), "ok");
        sandbox = new SandboxPath(root, root);
    }

    /**
     * Creates a link, or stops this test with a reason.
     *
     * <p>Not a silent skip: a run on a machine without the privilege says so, so
     * the coverage cannot quietly vanish on a platform.
     */
    private Path link(Path from, Path to) {
        try {
            return Files.createSymbolicLink(from, to);
        } catch (UnsupportedOperationException | IOException cannot) {
            Assumptions.abort("this filesystem will not create symbolic links here ("
                    + cannot.getClass().getSimpleName() + ": " + cannot.getMessage()
                    + "); on Windows this needs Developer Mode or the privilege");
            throw new AssertionError("unreachable");
        }
    }

    private void refuses(String candidate, String because) {
        assertThatThrownBy(() -> sandbox.resolve(candidate))
                .as(because)
                .isInstanceOf(CliException.class);
        assertThat(sandbox.contains(candidate)).as(because).isFalse();
    }

    // ------------------------------------------------------- dangling links

    @Nested
    @DisplayName("a link whose target does not exist yet")
    class Dangling {

        @Test
        @DisplayName("pointing outside is refused")
        void danglingOutwardIsRefused() {
            link(root.resolve("dangling-out"), outside.resolve("does-not-exist"));

            refuses("dangling-out",
                    "a link out of the sandbox is out of the sandbox, target or no target");
        }

        @Test
        @DisplayName("pointing outside is refused for a child of it too")
        void danglingOutwardChildIsRefused() {
            link(root.resolve("dangling-out"), outside.resolve("does-not-exist"));

            refuses("dangling-out/child.txt",
                    "what hangs off the link lands wherever the link points");
        }

        @Test
        @DisplayName("pointing outside through a relative target is refused")
        void relativeTargetIsRefused() {
            // ../outside/... rather than an absolute path: the target has to be
            // resolved against the directory the link sits in, not the process
            // working directory.
            link(root.resolve("relative-out"), Path.of("..", "outside", "not-there"));

            refuses("relative-out", "a relative link target resolves from the link, and leads out");
        }

        @Test
        @DisplayName("pointing inside is still allowed")
        void danglingInwardIsAllowed() {
            link(root.resolve("dangling-in"), root.resolve("inside/not-created-yet"));

            assertThat(sandbox.contains("dangling-in"))
                    .as("a path inside the sandbox that does not exist yet is one about to be made")
                    .isTrue();
        }

        @Test
        @DisplayName("the write it was refused for creates nothing outside")
        void nothingIsWrittenOutside() throws IOException {
            Path target = outside.resolve("does-not-exist");
            link(root.resolve("dangling-out"), target);

            assertThatThrownBy(() -> {
                Path resolved = sandbox.resolve("dangling-out");
                Files.writeString(resolved, "written through the link");
            }).isInstanceOf(CliException.class);

            assertThat(Files.exists(target, LinkOption.NOFOLLOW_LINKS))
                    .as("the whole point: nothing appeared outside the sandbox")
                    .isFalse();
        }
    }

    // ----------------------------------------------------------- live links

    @Nested
    @DisplayName("a link whose target exists")
    class Live {

        @Test
        @DisplayName("pointing outside is refused")
        void outwardIsRefused() {
            link(root.resolve("escape"), outside);

            refuses("escape/secret.txt", "following the link leaves the sandbox");
            refuses("escape", "the link itself leaves the sandbox");
        }

        @Test
        @DisplayName("staying inside is allowed")
        void inwardIsAllowed() {
            link(root.resolve("safe"), root.resolve("inside"));

            assertThat(sandbox.contains("safe/deep/file.txt"))
                    .as("a link that stays inside is an ordinary path")
                    .isTrue();
        }

        @Test
        @DisplayName("a linked parent directory cannot smuggle a child out")
        void linkedParentIsRefused() {
            link(root.resolve("escape"), outside);

            refuses("escape/not-created-yet.txt",
                    "the parent is the link; the child lands outside with it");
        }

        @Test
        @DisplayName("a chain of links out is refused")
        void chainIsRefused() {
            link(root.resolve("escape"), outside);
            link(root.resolve("chain"), root.resolve("escape"));

            refuses("chain/secret.txt", "a link to a link out is still out");
        }

        @Test
        @DisplayName("a cycle between links is refused rather than followed forever")
        void cycleIsRefused() {
            // Neither can be resolved, and a destination that cannot be
            // determined is not a destination that may be written to.
            link(root.resolve("loop-a"), root.resolve("loop-b"));
            link(root.resolve("loop-b"), root.resolve("loop-a"));

            refuses("loop-a", "an unresolvable cycle fails closed");
        }
    }

    // -------------------------------------------------- no link needed here

    @Nested
    @DisplayName("containment that needs no links, and runs everywhere")
    class WithoutLinks {

        @Test
        @DisplayName("parent traversal is refused")
        void traversalIsRefused() {
            refuses("../outside/secret.txt", "one level up is out");
            refuses("../../../../etc/passwd", "several levels up is still out");
        }

        @Test
        @DisplayName("an absolute path outside is refused")
        void absoluteOutsideIsRefused() {
            refuses(outside.resolve("secret.txt").toString(), "an absolute path out is out");
        }

        @Test
        @DisplayName("a sibling sharing a name prefix is refused")
        void prefixCollisionIsRefused() throws IOException {
            Path sibling = Files.createDirectories(base.resolve("sandbox-evil"));
            Files.writeString(sibling.resolve("f.txt"), "x");

            refuses(sibling.resolve("f.txt").toString(),
                    "sandbox-evil is not inside sandbox, however the strings compare");
        }

        @Test
        @DisplayName("an ordinary path that does not exist yet is still allowed")
        void newPathInsideIsAllowed() {
            assertThat(sandbox.contains("inside/deep/new-file.txt"))
                    .as("creating a file is the ordinary reason a path does not exist yet")
                    .isTrue();
            assertThat(sandbox.contains("brand/new/directory/tree.txt"))
                    .as("nor does a missing intermediate directory make it an escape")
                    .isTrue();
        }

        @Test
        @DisplayName("existing paths and the root itself behave as before")
        void ordinaryPathsAreUnchanged() {
            assertThat(sandbox.contains("inside/deep/file.txt")).isTrue();
            assertThat(sandbox.contains("./inside/./deep/../deep/file.txt")).isTrue();
            assertThat(sandbox.contains(".")).isTrue();
            // Spelled from the canonical root rather than the raw temporary
            // directory. Windows hands out 8.3 short names - REVANT~1 for
            // "Revanth Y" - and an absolute path written the short way does not
            // textually start with the resolved root, so it is refused. That is
            // the containment check erring towards refusal on a path that was in
            // fact inside, which is long-standing behaviour and not this change.
            assertThat(sandbox.contains(sandbox.root().resolve("inside").toString())).isTrue();
        }
    }
}
