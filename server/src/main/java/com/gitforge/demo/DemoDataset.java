package com.gitforge.demo;

import com.gitforge.auth.AuthService;
import com.gitforge.auth.dto.SignupRequest;
import com.gitforge.issue.IssueCommentService;
import com.gitforge.issue.IssueService;
import com.gitforge.issue.IssueStatus;
import com.gitforge.issue.dto.CreateCommentRequest;
import com.gitforge.issue.dto.CreateIssueRequest;
import com.gitforge.issue.dto.UpdateIssueRequest;
import com.gitforge.repo.RepoService;
import com.gitforge.repo.RepoVisibility;
import com.gitforge.repo.dto.CreateRepoRequest;
import com.gitforge.user.User;
import com.gitforge.user.UserService;
import com.gitforge.vcs.object.FileMode;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Signature;
import com.gitforge.vcs.repository.FileChange;
import com.gitforge.vcs.repository.VcsRepository;
import com.gitforge.vcsapi.VcsRepositoryProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The canonical demonstration dataset.
 *
 * <p>Every repository here exists to show something the engine actually does -
 * a merge that conflicts five different ways, a history long enough to page
 * through, a diff of every shape, an empty repository for the empty states.
 * Nothing is scratch work and nothing is decorative.
 *
 * <p>Accounts, repositories and issues are created through the application's own
 * services, so the demo data goes through the same validation and authorization
 * as anything a user creates. Commits go one level deeper, to the engine's
 * {@code CommitService}, because only there can the author's timestamp be chosen
 * - and a contribution calendar with every commit on the same afternoon
 * demonstrates nothing. The repository handle still comes from
 * {@link VcsRepositoryProvider}, so even the seeder cannot reach storage without
 * passing the ownership check.
 *
 * <p>Dates are offsets from a single epoch. Leave {@code gitforge.demo.epoch}
 * unset and that is "now", so the calendar looks alive; pin it and the whole
 * dataset - object ids included - is reproducible byte for byte, which is
 * content addressing being exactly as advertised.
 */
@Component
@Profile(DemoDataSeeder.PROFILE)
public class DemoDataset {

    private static final String OWNER = "forge-demo";
    private static final String COLLABORATOR = "forge-viewer";

    /**
     * One line past the point where the blob view stops numbering.
     *
     * <p>The threshold is five thousand. Crossing it by a single line is enough
     * to reach the fallback and keeps the fixture as small as it can honestly
     * be.
     */
    private static final int MANY_LINES = 5_001;

    private final AuthService authService;
    private final UserService userService;
    private final RepoService repoService;
    private final IssueService issues;
    private final IssueCommentService comments;
    private final VcsRepositoryProvider repositories;
    private final DemoProperties properties;

    public DemoDataset(
            AuthService authService,
            UserService userService,
            RepoService repoService,
            IssueService issues,
            IssueCommentService comments,
            VcsRepositoryProvider repositories,
            DemoProperties properties) {

        this.authService = authService;
        this.userService = userService;
        this.repoService = repoService;
        this.issues = issues;
        this.comments = comments;
        this.repositories = repositories;
        this.properties = properties;
    }

    private Instant epoch;

    public void seed(Instant seedEpoch) {
        this.epoch = seedEpoch;

        User owner = account(OWNER, "forge-demo@gitforge.test");
        User collaborator = account(COLLABORATOR, "forge-viewer@gitforge.test");

        engine(owner, collaborator);
        dag(owner);
        merges(owner);
        diffs(owner);
        longHistory(owner);
        branching(owner);
        empty(owner);
        privateWork(owner);
        collaboratorsOwnWork(collaborator);

        discussion(owner, collaborator);
    }

    // ---------------------------------------------------------------- accounts

    private User account(String username, String email) {
        authService.signup(new SignupRequest(username, email, properties.password()));
        return userService.requireByUsername(username);
    }

    // ------------------------------------------------------------ repositories

    /** The flagship: what someone should look at first. */
    private void engine(User owner, User collaborator) {
        VcsRepository repo = create(owner, "gitforge-engine",
                "A version control system built from first principles: SHA-1 content addressing, "
                        + "Merkle trees, a commit DAG and three-way merge.");

        ObjectId first = commit(repo, owner, 340, "main", "Initial commit",
                put("README.md", """
                        # gitforge-engine

                        Content-addressable storage, implemented without a Git library.

                        An object's identity is the SHA-1 of `<type> <length>\\0<payload>`.
                        Change one byte of content and you have a different object; store
                        the same file twice and you have stored it once.
                        """),
                put("LICENSE", "MIT\n"));

        commit(repo, owner, 300, "main", "Add the object model",
                put("src/object/Blob.java", "final class Blob {}\n"),
                put("src/object/Tree.java", "final class Tree {}\n"),
                put("src/object/Commit.java", "final class Commit {}\n"));

        commit(repo, collaborator, 250, "main", "Hash objects the way the format specifies",
                put("src/hash/Sha1.java", "final class Sha1 {}\n"),
                put("README.md", """
                        # gitforge-engine

                        Content-addressable storage, implemented without a Git library.

                        An object's identity is the SHA-1 of `<type> <length>\\0<payload>`.
                        Change one byte of content and you have a different object; store
                        the same file twice and you have stored it once.

                        ## Objects

                        | Type | Holds |
                        |---|---|
                        | blob | file contents |
                        | tree | a directory listing |
                        | commit | a tree, its parents, and who wrote it |
                        """));

        commit(repo, owner, 120, "main", "Walk trees without loading what has not changed",
                put("src/tree/TreeWalker.java", "final class TreeWalker {}\n"),
                put("src/tree/MerkleTree.java", "final class MerkleTree {}\n"));

        commit(repo, collaborator, 40, "main", "Diff two trees",
                put("src/diff/TreeDiffer.java", "final class TreeDiffer {}\n"));

        commit(repo, owner, 6, "main", "Merge, three ways",
                put("src/merge/ThreeWayMerger.java", "final class ThreeWayMerger {}\n"),
                put("src/merge/ConflictKind.java", "enum ConflictKind {}\n"));

        // A branch left open, so the branch list has something live on it.
        repo.branches().createBranch("feature/pack-files", first);
        commit(repo, owner, 3, "feature/pack-files", "Sketch a pack format",
                put("docs/packfile.md", "Objects stored individually today. One file each.\n"));
    }

    /** Branched and merged history, for the commit graph. */
    private void dag(User owner) {
        VcsRepository repo = create(owner, "dag-demo",
                "Branched and merged history, for the commit graph.");

        ObjectId root = commit(repo, owner, 60, "main", "Initial commit",
                put("README.md", "# dag-demo\n\nA history with shape.\n"));

        commit(repo, owner, 55, "main", "Work on the trunk",
                put("trunk.txt", "one\n"));

        repo.branches().createBranch("topic/left", root);
        commit(repo, owner, 50, "topic/left", "Take the left fork",
                put("left.txt", "left\n"));

        repo.branches().createBranch("topic/right", root);
        commit(repo, owner, 48, "topic/right", "Take the right fork",
                put("right.txt", "right\n"));

        merge(repo, owner, 44, "main", "topic/left", "Merge the left fork");
        commit(repo, owner, 40, "main", "Carry on",
                put("trunk.txt", "one\ntwo\n"));
        merge(repo, owner, 30, "main", "topic/right", "Merge the right fork");

        // Left unmerged on purpose: a graph where everything has been merged
        // shows nothing about how an open branch is drawn.
        repo.branches().createBranch("topic/unmerged", root);
        commit(repo, owner, 20, "topic/unmerged", "Something still in progress",
                put("wip.txt", "not finished\n"));

        commit(repo, owner, 5, "main", "Latest on main",
                put("trunk.txt", "one\ntwo\nthree\n"));
    }

    /**
     * Every merge outcome, including all five conflict kinds.
     *
     * <p>Each conflict gets its own pair of branches: one merge cannot produce
     * five kinds at once without the fixture becoming impossible to follow.
     */
    private void merges(User owner) {
        VcsRepository repo = create(owner, "merge-demo",
                "Every merge outcome: already up to date, fast-forward, clean merge, "
                        + "and all five kinds of conflict.");

        ObjectId base = commit(repo, owner, 30, "main", "Initial commit",
                put("README.md", "# merge-demo\n"),
                put("shared.txt", "one\ntwo\nthree\n"),
                put("doomed.txt", "delete me\n"),
                put("script.sh", "echo hello\n"));

        // Clean three-way: both sides moved, but not over each other.
        repo.branches().createBranch("ready/clean", base);
        commit(repo, owner, 26, "ready/clean", "Touch a file nobody else touched",
                put("clean.txt", "mine\n"));
        commit(repo, owner, 25, "main", "Touch a different file",
                put("theirs.txt", "yours\n"));

        // Already up to date: main is already past this, so there is nothing to
        // bring in.
        repo.branches().createBranch("ready/already-merged", base);

        // CONTENT: both sides rewrote the same lines.
        repo.branches().createBranch("conflict/content", base);
        commit(repo, owner, 24, "conflict/content", "Rewrite the middle",
                put("shared.txt", "one\nTHEIRS\nthree\n"));
        commit(repo, owner, 23, "main", "Rewrite the middle differently",
                put("shared.txt", "one\nOURS\nthree\n"));

        // ADD_ADD: neither side had the file; both invented it.
        repo.branches().createBranch("conflict/add-add", base);
        commit(repo, owner, 22, "conflict/add-add", "Add a new file",
                put("invented.txt", "their version\n"));
        commit(repo, owner, 21, "main", "Add the same new file",
                put("invented.txt", "our version\n"));

        // MODIFY_DELETE: one side edited what the other removed.
        repo.branches().createBranch("conflict/modify-delete", base);
        commit(repo, owner, 20, "conflict/modify-delete", "Edit the file",
                put("doomed.txt", "actually, keep me\n"));
        commit(repo, owner, 19, "main", "Delete the file",
                FileChange.delete("doomed.txt"));

        // MODE: both sides made the same edit and disagreed only about whether
        // the result is executable. Identical bytes, so there is nothing to
        // reconcile except the mode - which is the whole point of the kind.
        repo.branches().createBranch("conflict/mode", base);
        commit(repo, owner, 18, "conflict/mode", "Update the script and make it executable",
                FileChange.put("script.sh", bytes("echo updated\n"), FileMode.EXECUTABLE_FILE));
        commit(repo, owner, 17, "main", "Update the script, leaving it non-executable",
                put("script.sh", "echo updated\n"));

        // TYPE: a file on one side, a directory on the other.
        repo.branches().createBranch("conflict/type", base);
        commit(repo, owner, 16, "conflict/type", "Make it a file",
                put("thing", "I am a file\n"));
        commit(repo, owner, 15, "main", "Make it a directory",
                put("thing/inside.txt", "I am a directory\n"));

        // Fast-forward: cut from where main now stands, so main is its ancestor
        // and merging it needs no commit at all. Created last for that reason -
        // any commit landing on main afterwards would turn this into an
        // ordinary three-way merge.
        repo.branches().createBranch("ready/fast-forward", repo.branches().getBranch("main").orElseThrow());
        commit(repo, owner, 14, "ready/fast-forward", "A change with nothing to reconcile",
                put("fast-forward.txt", "ahead\n"));
    }

    /** Every diff shape the viewer has to render. */
    private void diffs(User owner) {
        VcsRepository repo = create(owner, "diff-demo",
                "Every diff shape: added, deleted, modified, mode-only, binary, "
                        + "long lines and deeply nested paths.");

        commit(repo, owner, 14, "main", "Initial commit",
                put("README.md", "# diff-demo\n"),
                put("modified.txt", "one\ntwo\nthree\nfour\nfive\n"),
                put("removed.txt", "this file goes away\n"),
                put("mode.sh", "echo mode\n"),
                put("very/deeply/nested/directory/structure/for/path/rendering/file.txt", "deep\n"),
                FileChange.put("logo.bin", pngBytes(), FileMode.REGULAR_FILE));

        commit(repo, owner, 7, "main", "Every kind of change at once",
                // modified
                put("modified.txt", "one\nTWO\nthree\nfour\nFIVE\nsix\n"),
                // deleted
                FileChange.delete("removed.txt"),
                // added
                put("added.txt", "brand new\n"),
                // mode only: identical bytes, different mode
                FileChange.put("mode.sh", bytes("echo mode\n"), FileMode.EXECUTABLE_FILE),
                // a line long enough to force horizontal scrolling
                put("long-line.txt", "x".repeat(2_000) + "\n"),
                // binary: the differ declines to line-diff it
                FileChange.put("logo.bin", pngBytes(2), FileMode.REGULAR_FILE));

        /* Two files that are unremarkable to store and awkward to display: one
           with nothing in it at all, and one with more lines than the blob view
           will number. Both states were written long ago and neither had ever
           been seen against real data, because nothing in this dataset was
           empty and nothing was anywhere near long enough. */
        commit(repo, owner, 5, "main", "Add files that are awkward to display",
                put("empty.txt", ""),
                put("many-lines.txt", numberedLines(MANY_LINES)));
    }

    /** Long enough that the history has to be paged through. */
    private void longHistory(User owner) {
        VcsRepository repo = create(owner, "long-history",
                "A history long enough to page through.");

        for (int i = 1; i <= 60; i++) {
            commit(repo, owner, 200 - i * 3, "main", "Commit number " + i,
                    put("log.txt", "entry " + i + "\n"),
                    put("counter.txt", String.valueOf(i) + "\n"));
        }
    }

    /** Branch creation, deletion, HEAD movement and slash-separated names. */
    private void branching(User owner) {
        VcsRepository repo = create(owner, "branching-demo",
                "For exercising branch creation, deletion and HEAD movement.");

        ObjectId base = commit(repo, owner, 12, "main", "Initial commit",
                put("README.md", "# branching-demo\n"));

        repo.branches().createBranch("feature/login", base);
        commit(repo, owner, 10, "feature/login", "Start the sign-in form",
                put("login.txt", "a form\n"));

        repo.branches().createBranch("release/1.0", base);
        commit(repo, owner, 9, "release/1.0", "Cut the release",
                put("VERSION", "1.0\n"));

        repo.branches().createBranch("bugfix/auth/token", base);
        commit(repo, owner, 8, "bugfix/auth/token", "Fix token expiry",
                put("token.txt", "expires\n"));
    }

    /** No commits at all, for the empty states. */
    private void empty(User owner) {
        create(owner, "no-history", "An empty repository, for the empty states.");
    }

    /** Visible to its owner and to nobody else. */
    private void privateWork(User owner) {
        VcsRepository repo = create(owner, "private-experiments", RepoVisibility.PRIVATE,
                "Not visible to anyone but its owner.");

        commit(repo, owner, 11, "main", "Something unfinished",
                put("notes.md", "# Private notes\n\nOnly the owner can read this.\n"));
    }

    /**
     * A second owner, so authorization has a real counterparty.
     *
     * <p>They need repositories of their own for their profile to show anything:
     * contributions are counted within the subject's own repositories, so their
     * commits over in {@code gitforge-engine} do not appear on their calendar.
     */
    private void collaboratorsOwnWork(User collaborator) {
        VcsRepository repo = create(collaborator, "reading-notes",
                "Notes taken while reading through the engine.");

        commit(repo, collaborator, 90, "main", "Start taking notes",
                put("README.md", "# reading-notes\n\nWorking through the object model.\n"));

        commit(repo, collaborator, 63, "main", "On Merkle trees",
                put("merkle.md", """
                        A directory's hash covers everything beneath it, so two trees
                        with the same id are identical all the way down and can be
                        skipped without looking inside.
                        """));

        commit(repo, collaborator, 21, "main", "On merge bases",
                put("merge-bases.md", "The best common ancestor decides what counts as a change.\n"));

        commit(repo, collaborator, 4, "main", "On the diff algorithm",
                put("myers.md", "Shortest edit script, walked as a graph.\n"));
    }

    // ----------------------------------------------------------------- issues

    private void discussion(User owner, User collaborator) {
        var reported = issues.create(OWNER, "gitforge-engine", collaborator, new CreateIssueRequest(
                "Abbreviated object ids are not accepted as a start point",
                "Creating a branch from a 7-character id fails; only the full 40 characters resolve.\n\n"
                        + "Not a blocker, but it is the first thing anyone tries."));

        comments.create(OWNER, "gitforge-engine", reported.number(), owner, new CreateCommentRequest(
                "Confirmed. Resolution takes the whole id or nothing - there is no prefix search "
                        + "over the object store yet."));

        var paging = issues.create(OWNER, "gitforge-engine", owner, new CreateIssueRequest(
                "History is capped at 200 commits with no cursor",
                "`GET /commits` clamps `limit` to 200. Longer histories need a cursor rather than "
                        + "a bigger cap."));

        comments.create(OWNER, "gitforge-engine", paging.number(), collaborator, new CreateCommentRequest(
                "The commit graph pages client-side for now, which holds up to the cap and not past it."));

        issues.create(OWNER, "gitforge-engine", owner, new CreateIssueRequest(
                "No rename detection in the tree differ",
                "A renamed file shows as a delete and an add. Detecting the pair means comparing "
                        + "content similarity across the two sides, which the differ does not do."));

        // Closed below, so the issue list has both states to filter between.
        var closed = issues.create(OWNER, "dag-demo", collaborator, new CreateIssueRequest(
                "Graph gutters drift on very wide diffs",
                "Sticky table cells need `border-collapse: separate` in Chrome."));

        comments.create(OWNER, "dag-demo", closed.number(), owner, new CreateCommentRequest(
                "Reproduced at 390px. Fixed by separating the borders."));

        issues.update(closed.id(), owner, new UpdateIssueRequest(null, null, IssueStatus.CLOSED));
    }

    // ----------------------------------------------------------------- helpers

    private VcsRepository create(User owner, String name, String description) {
        return create(owner, name, RepoVisibility.PUBLIC, description);
    }

    private VcsRepository create(User owner, String name, RepoVisibility visibility, String description) {
        repoService.create(owner, new CreateRepoRequest(name, description, visibility));
        return repositories.forWrite(owner.getUsername(), name, owner);
    }

    /**
     * @param daysAgo how far before the epoch this commit was authored, so the
     *     contribution calendar has something spread across it
     */
    private ObjectId commit(
            VcsRepository repo, User author, int daysAgo, String branch, String message, FileChange... changes) {

        Signature signature = Signature.of(
                author.getUsername(), author.getEmail(), epoch.minus(Duration.ofDays(daysAgo)));

        return repo.commits().commit(branch, List.of(changes), signature, message);
    }

    private void merge(VcsRepository repo, User author, int daysAgo, String ours, String theirs, String message) {
        Signature signature = Signature.of(
                author.getUsername(), author.getEmail(), epoch.minus(Duration.ofDays(daysAgo)));

        repo.merges().merge(ours, theirs, signature, message);
    }

    private static FileChange put(String path, String content) {
        return FileChange.put(path, bytes(content), FileMode.REGULAR_FILE);
    }

    private static byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Numbered lines, one per line, ending with a newline.
     *
     * <p>Deterministic by construction: a given count always produces the same
     * bytes, so the blob's id is fixed and a test can name it outright rather
     * than recomputing what the code just did.
     */
    static String numberedLines(int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            text.append("line ").append(i).append('\n');
        }
        return text.toString();
    }

    /**
     * A real PNG header followed by filler.
     *
     * <p>Binary because of the NUL bytes in it, which is exactly the test the
     * differ applies - so this file is declined for line diffing for the same
     * reason a genuine image would be.
     *
     * @param variant changes the payload so two versions differ
     */
    private static byte[] pngBytes(int variant) {
        byte[] png = new byte[512];
        byte[] header = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(header, 0, png, 0, header.length);

        for (int i = header.length; i < png.length; i++) {
            png[i] = (byte) ((i * 31 + variant * 7) % 256);
        }
        return png;
    }

    private static byte[] pngBytes() {
        return pngBytes(1);
    }
}
