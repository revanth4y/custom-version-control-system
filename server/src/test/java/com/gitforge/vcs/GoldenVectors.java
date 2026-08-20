package com.gitforge.vcs;

/**
 * Object ids produced by an independent, battle-tested implementation of the
 * same object format.
 *
 * <p>These constants were generated once during development with Git 2.51
 * ({@code git hash-object}, {@code git mktree}) and copied here verbatim. They
 * exist so the hashing is checked against something other than itself: a
 * self-consistent implementation with a wrong header, a wrong length, or a wrong
 * entry ordering would still pass round-trip tests, and every symptom downstream
 * would surface only as "the hashes do not match".
 *
 * <p>Nothing in the production code or the test suite invokes Git. Git is not
 * required to build or run this project.
 *
 * <p>Commands used, for reproducibility:
 *
 * <pre>
 *   printf 'hello world' | git hash-object --stdin
 *   printf '100644 blob &lt;id&gt;\tApp.java\n...' | git mktree
 * </pre>
 */
public final class GoldenVectors {

    private GoldenVectors() {
    }

    // ---- Blobs -------------------------------------------------------------

    /** {@code printf '' | git hash-object --stdin} */
    public static final String EMPTY_BLOB = "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391";

    /** {@code printf 'hello world' | git hash-object --stdin} */
    public static final String BLOB_HELLO_WORLD = "95d09f2b10159347eece71399a7e2e907ea3df4f";

    /** {@code printf 'hello world\n' | git hash-object --stdin} */
    public static final String BLOB_HELLO_WORLD_NEWLINE = "3b18e512dba79e4c8300dd08aeb37f8e728b8dad";

    /** {@code printf 'abc' | git hash-object --stdin} */
    public static final String BLOB_ABC = "f2ba8f84ab5c1bce84a7b441cb1959cfc7093b7f";

    /** {@code printf 'a' | git hash-object --stdin} */
    public static final String BLOB_A = "2e65efe2a145dda7ee51d1741299f848e5bf752e";

    /** {@code printf 'b' | git hash-object --stdin} */
    public static final String BLOB_B = "63d8dbd40c23542e740659a7168a0ce3138ea748";

    /** Bytes {@code 00 01 02 FF FE} — binary content including an embedded NUL. */
    public static final String BLOB_BINARY = "bfa7018e30c510999bbe462316bdd3839a58aa56";

    // ---- The demo repository ----------------------------------------------
    //
    //   ROOT
    //   |-- README.md      "# Demo\n"
    //   |-- pom.xml        "<project/>\n"
    //   `-- src/
    //       |-- App.java   "class App {}\n"
    //       `-- User.java  "class User {}\n"

    public static final String BLOB_README = "0805455a24b6c68fbc38d0fa5d121f735984285d";
    public static final String BLOB_POM = "a1e54a01d8732ffb2867907852669a3ee5c081fb";
    public static final String BLOB_APP_JAVA = "c9ff7826ecbf3abd69c4dd08dbaddd463d9d9055";
    public static final String BLOB_USER_JAVA = "294ac97d4de39806ea36567809d357b2eda70329";

    /** The {@code src/} subtree. */
    public static final String TREE_SRC = "5e389ba64aa3ec15338cc042ccd5d1c9f222f924";

    /** The Merkle root of the demo repository. */
    public static final String TREE_ROOT = "d760777a57381fefbb8cf06e181830d74ed2fae2";

    /** {@code git mktree} with no input. */
    public static final String EMPTY_TREE = "4b825dc642cb6eb9a060e54bf8d69288fbee4904";

    // ---- Ordering fixtures -------------------------------------------------

    public static final String BLOB_A_NEWLINE = "f70f10e4db19068f79bc43844b49f3eece45c4e8";
    public static final String BLOB_B_NEWLINE = "223b7836fb19fdf64ba2d3cd6173c6a283141f78";

    /** A tree holding a single file {@code inner.txt}. */
    public static final String TREE_SUB = "cf26462c35d1aee97693d1bb68f77ac123d9f54c";

    /** Entries {@code src} (directory) and {@code src.txt} (file): the file sorts first. */
    public static final String TREE_SRC_AND_SRC_TXT = "39f593c87a6f31cdc0bcca9292d972bc7269bb23";

    /** Entries {@code src} (directory) and {@code src0} (file): the directory sorts first. */
    public static final String TREE_SRC_AND_SRC0 = "1b665cb5d53284151444ec098d10a8134e47e458";

    /** A tree holding one executable file {@code run.sh}. */
    public static final String TREE_EXECUTABLE = "cf0101fa4f3adf029be824f70f016042998940b9";

    // ---- Commits -----------------------------------------------------------
    //
    // Produced with git commit-tree against TREE_ROOT, with both author and
    // committer fixed as:
    //     Ada Lovelace <ada@example.com> 1700000000 +0000
    //
    //   export GIT_AUTHOR_NAME="Ada Lovelace"
    //   export GIT_AUTHOR_EMAIL="ada@example.com"
    //   export GIT_AUTHOR_DATE="1700000000 +0000"
    //   (and the matching GIT_COMMITTER_NAME / _EMAIL / _DATE)
    //   git commit-tree <tree> [-p <parent>]... -m "<message>"

    /** No parents, message "Initial commit". */
    public static final String COMMIT_INITIAL = "7aa5addf4dbf73b4b8310cddf4cf1e9cd7232bdb";

    /** Parent COMMIT_INITIAL, message "Second commit". */
    public static final String COMMIT_SECOND = "6a3c521d994a512e6c946ee57d703a2b9edeeb3d";

    /** Parent COMMIT_INITIAL, message "Branch commit". */
    public static final String COMMIT_BRANCH = "39d871fb65e3d1c9b335375cb2df2f4759acfe27";

    /** Parents [COMMIT_SECOND, COMMIT_BRANCH], message "Merge branch". */
    public static final String COMMIT_MERGE = "8706aaa0e767185c33c48bc486585b450e4b455f";

    /** The same merge with its parents swapped: a different commit. */
    public static final String COMMIT_MERGE_PARENTS_SWAPPED = "2d1bf264ba11e1fd9f2a2156b4f5aa280cf51cb7";

    /** COMMIT_INITIAL's metadata at the same instant but offset +0530. */
    public static final String COMMIT_OFFSET_0530 = "836fedc2e22a9721276e031f8e6d54aa225b037a";

    /** The fixed signature used by all commit vectors. */
    public static final String SIGNATURE_NAME = "Ada Lovelace";
    public static final String SIGNATURE_EMAIL = "ada@example.com";
    public static final long SIGNATURE_EPOCH_SECONDS = 1_700_000_000L;
}
