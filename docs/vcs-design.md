# The version control engine

Written from the ground up with the Java standard library. No JGit, no `git`
subprocess, no VCS dependency of any kind. This document describes what it does
and, where it matters, where it deliberately parts company with Git.

## Object identity

Every object is named by the SHA-1 of its canonical form:

```
<type> <length>\0<payload>
```

`blob`, `tree` or `commit`; the payload's length in bytes as decimal ASCII; a NUL;
then the payload. `blob 5\0hello` hashes to
`b6fc4c620b67d95f953a5c1c1230aaab5db5a1b0`.

Two properties follow, and most of the engine rests on them:

- **Identical content yields an identical id.** Store the same file in two
  repositories and both compute the same name for it.
- **An id is verifiable.** Re-hash the payload; if it does not reproduce the name
  the object was filed under, the object is corrupt. `CorruptObjectException`
  exists for exactly that.

SHA-1 is implemented from the specification in `vcs/hash/Sha1.java` — message
padding, the 80-round schedule, the four round functions — and checked against
published test vectors and against `MessageDigest` on random input.

SHA-1 is used because Git uses it and the framing is worth matching. It is
cryptographically broken; nothing here treats it as a security boundary.

## The object model

### Blob

File content, nothing else. No name, no mode, no timestamp — those belong to
whatever points at the blob. Two files with the same bytes are one blob.

### Tree

A directory listing. Each entry is a mode, a name, and the id of a blob or
subtree:

```
100644 README.md    <blob id>
100755 build.sh     <blob id>
40000  src          <tree id>
```

Entries are sorted canonically, so the same directory always serialises to the
same bytes and therefore the same id. Modes are written verbatim into the payload
and so feed the hash directly — note that directories are stored as `40000`,
without the leading zero the octal form conventionally shows.

### Commit

A root tree, zero or more parents, an author, a committer, and a message. A first
commit has no parents; a merge has two. Author and committer are separate because
they genuinely differ on a merge.

## The Merkle property

A tree's id covers every byte beneath it. Change one character in one file and
that file's blob id changes, so its directory's tree id changes, so every tree
above it changes, all the way to the root — and therefore the commit id.

This is not decoration. `TreeDiffer` compares two trees by walking them together;
whenever both sides show the same subtree id, the **entire subtree is skipped**
without reading a single object. A commit touching one file in a large repository
costs a walk proportional to the depth of that file, not to the size of the tree.
`TreeDifferTest` asserts this directly, with a counting object store that fails
the test if a skipped subtree is ever read.

## The commit DAG

Commits form a directed acyclic graph through parent links. `CommitGraph` offers
both traversals, because they answer different questions.

**BFS** visits in order of distance from the start. In a diamond history the
shared ancestor appears after both sides of the diamond, which is the property
reachability and common-ancestor work depend on.

**DFS** follows one line of descent to its end before backtracking, in preorder —
what you want when tracing where a change came from.

Both are iterative, not recursive: a deep history would otherwise overflow the
stack, and "deep" here means a few thousand commits.

`isAncestor` short-circuits the moment it finds the target rather than
materialising the full ancestor set.

### Merge bases

The **lowest common ancestors** of two commits: those reachable from both, minus
any that another candidate can itself reach.

```
      A───B───C     ← ours
     /
    O
     \
      D───E         ← theirs
```

`O` is the merge base. In a criss-cross history there can be several, and none is
more correct than the others, so `mergeBases` returns all of them, sorted by
object id — arbitrary but stable, and deliberately independent of traversal
order, so swapping the arguments cannot change the result.

> **Divergence from Git.** When several bases exist, `MergeOrchestrator` takes the
> first and merges against it. Git's `recursive` strategy merges the bases
> together and uses the result as a virtual base. Ours is deterministic and
> symmetric, but for criss-cross histories it is one valid answer among several
> rather than the best one.

## Branches and HEAD

A branch is a file under `refs/heads/` containing an object id. HEAD is a file
naming a branch:

```
<repo-uuid>/
  objects/<xx>/<38 hex>
  refs/heads/main
  refs/heads/feature/login
  HEAD
```

A branch comes into being with its first commit — an empty repository has a HEAD
pointing at a branch that does not exist yet, which is exactly how an empty
repository behaves.

Commit order is deliberate: write the blobs, build and write the trees, write the
commit, and only then move the branch. A failure part-way leaves unreferenced
objects on disk, which are harmless, rather than a branch pointing at a commit
that was never written, which is not.

**Branch names** are validated by rules of our own (`vcs/ref/BranchName.java`).
Slashes are allowed, so `feature/login` and `bugfix/auth/token` work and nest as
directories under `refs/heads/`. The rules reject what would make a ref
ambiguous or a path unsafe.

> **Divergence from Git.** No reflog, no packed-refs, no remote-tracking
> branches, no tags. Every object is stored individually — there are no pack
> files, so a large repository costs one file per object.

## Diffing

### Tree diff

`TreeDiffer` walks two trees in parallel and reports each path as added, deleted
or modified, skipping matching subtrees as described above. It also detects when
a path is a file on one side and a directory on the other, which the merger needs.

> **Divergence from Git.** No rename detection. A file that moved shows as a
> delete and an add. Pairing them means scoring content similarity across both
> sides, which is not attempted.

### Line diff

`LineDiffer` implements Myers' O(ND) shortest-edit-script algorithm: walk the edit
graph by increasing edit distance `d`, tracking the furthest-reaching path on
each diagonal, and reconstruct the path backwards once the end is reached. For
similar files — the common case — `d` is small and the work is close to linear.

Output is grouped into hunks with three lines of context, and hunks close enough
that their context would overlap are merged into one.

It is bounded on purpose:

| Bound | Value | Why |
|---|---|---|
| `MAX_LINES` | 20,000 | Beyond this a line diff is not readable anyway |
| `MAX_EDIT_DISTANCE` | 5,000 | Two unrelated files of any size would otherwise run to `n + m` |
| `MAX_FILES_WITH_HUNKS` | 100 | A commit touching a thousand files still responds |

Exceeding a bound is not an error: the file is reported as changed, with no line
diff. The UI says so plainly rather than pretending nothing happened.

**Binary content is never line-diffed.** `TextContent` classifies content as text
only if it holds no NUL byte and decodes strictly as UTF-8 — strictly, so that a
binary file cannot slip through as replacement characters.

## Three-way merge

Given two branch tips, `ThreeWayMerger` finds a merge base and compares all three
trees path by path.

| Base | Ours | Theirs | Result |
|---|---|---|---|
| X | X | Y | take theirs |
| X | Y | X | take ours |
| X | Y | Y | both agree, take it |
| X | Y | Z | **conflict** |

Where both sides made a directory of the same path, the merge recurses into it —
that is not a conflict, just a smaller instance of the same problem. Where the
base held something else there, both sides independently created a directory, so
the recursion merges them against the empty tree.

### The five conflict kinds

| Kind | Condition |
|---|---|
| `CONTENT` | Present in the base; both sides changed it differently |
| `ADD_ADD` | Absent from the base; both sides created it with different content |
| `MODIFY_DELETE` | One side changed it, the other removed it |
| `MODE` | Both sides produced the same bytes but disagree on the mode |
| `TYPE` | One side has a file where the other has a directory |

Classification is read off the data rather than tracked through the walk: if one
side is a directory it is `TYPE`; if one side is absent it is `MODIFY_DELETE`; if
both blob ids match it can only be `MODE`; otherwise it is `ADD_ADD` when the base
had nothing and `CONTENT` when it did.

### What a conflict does not do

**A conflicted merge writes nothing.** No object is persisted and no branch moves.
The entire result is computed first, and only a clean merge is committed. The
alternative — discovering the conflict half-way, with some blobs already stored
and the branch already moved — is how a merge leaves a repository in a state
nobody asked for.

**Every conflict is found in one pass.** The walk continues past the first
conflict, using our side as a placeholder so the traversal can proceed, and the
tree built from those placeholders is discarded. You see the whole picture before
deciding what to do.

> **Divergence from Git.** Resolution is at file level. A conflict reports the
> path, the kind, and the object on each side; it does not write `<<<<<<<`
> markers into the file, and there is no index or working tree to resolve them
> in. GitForge is a server: there is no checkout for anyone to edit.

### Merge outcomes

| Outcome | When |
|---|---|
| `ALREADY_UP_TO_DATE` | Their branch is already an ancestor of ours |
| `FAST_FORWARDED` | Ours is an ancestor of theirs — the pointer moves, no merge commit |
| `MERGED` | A real three-way merge, producing a merge commit with two parents |
| `CONFLICTED` | Nothing written, nothing moved |

## Storage

Objects live at `objects/<first two hex chars>/<remaining 38>`, zlib-compressed.
The two-character shard keeps any one directory to a manageable size.

Compression never enters the hash: identity is the SHA-1 of the *uncompressed*
canonical form, so the compression scheme could change without rewriting a single
id.

Writes are atomic — a temporary file, then a move — so a reader never sees a
half-written object, and a crash mid-write leaves a stray temp file rather than a
corrupt one.

Repositories are isolated structurally. `VcsRepositoryFactory` is the only place a
`RepositoryId` becomes a filesystem path; there is no shared store and no route
from one repository's services to another's storage. Two repositories holding the
same file compute the same object id — content addressing is global by nature —
but each keeps its own copy, and neither can read the other's.

> **No garbage collection.** Objects left unreachable — by a deleted branch, or
> by a merge that was computed and discarded — stay on disk. Nothing reads them
> and nothing reclaims them.

## What is not here

Honest list, so nothing above reads as more than it is:

- No pack files, no delta compression — one file per object
- No rename detection
- No abbreviated object ids; revisions are `HEAD`, a branch name, or a full 40 characters
- No tags, no remotes, no reflog, no packed-refs
- No garbage collection
- No line-level conflict resolution
- No submodules, no symlink entries, no `.gitignore` semantics
- Criss-cross merges pick one base rather than merging bases recursively
- Single-node storage: no replication, no sharding

## Tests

The engine's tests never start an application context. Beyond the usual cases they
pin the properties the design claims:

- Object ids are checked against an independent implementation, and against
  published SHA-1 vectors
- Trees serialise identically across runs, and re-hash to the id they were stored
  under
- A subtree with a matching id is never read during a diff — enforced by a
  counting store that fails the test if it is
- A conflicted merge leaves the object store byte-for-byte unchanged
- BFS is proved not to be a topological order, with the fixture that proved it
- A merge produces the same result with its arguments swapped
