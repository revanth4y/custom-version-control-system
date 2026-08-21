/**
 * Real issue and comment payloads captured from the running server on
 * forge-dev/dag-demo.
 *
 * Recorded rather than invented so the awkward cases are the real ones: an
 * issue with an empty body, a very long title, a Markdown body, closed and open
 * states, and - because the schema clears an author rather than cascading -
 * an issue and a comment whose author account no longer exists.
 */

/** Seven issues, newest number first, as the list endpoint returns them. */
export const issueList = [
  {
    "id": "4a77c7f9-018a-4bd4-8ebe-63f9589eef81",
    "number": 7,
    "title": "Compare page should remember the last pair",
    "body": "Low priority.",
    "status": "OPEN",
    "authorUsername": "forge-dev",
    "createdAt": "2026-08-21T05:12:46.353973Z",
    "updatedAt": "2026-08-21T05:12:46.353973Z"
  },
  {
    "id": "a83a439f-494a-4c49-863f-ce32bdc10187",
    "number": 6,
    "title": "Support abbreviated SHAs as a branch start point",
    "body": "`POST /branches` rejects a 7-character id; only full 40-character ids resolve.",
    "status": "OPEN",
    "authorUsername": null,
    "createdAt": "2026-08-21T05:12:45.934817Z",
    "updatedAt": "2026-08-21T05:12:45.934817Z"
  },
  {
    "id": "ecbc8663-8322-4fbb-a890-64334279aea2",
    "number": 5,
    "title": "Diff gutters scroll away on very long lines",
    "body": "Sticky cells need `border-collapse: separate`.",
    "status": "CLOSED",
    "authorUsername": "forge-viewer",
    "createdAt": "2026-08-21T05:12:45.513841Z",
    "updatedAt": "2026-08-21T05:12:48.921089Z"
  },
  {
    "id": "6fad8e20-4bfc-4a44-b0a6-5552dcacbe5b",
    "number": 4,
    "title": "Add a keyboard shortcut for the branch selector",
    "body": "",
    "status": "OPEN",
    "authorUsername": "forge-dev",
    "createdAt": "2026-08-21T05:12:45.090234Z",
    "updatedAt": "2026-08-21T05:12:45.090234Z"
  },
  {
    "id": "00a47cab-ac48-4437-a35b-e98c439b1242",
    "number": 3,
    "title": "Blob viewer should not decode binary files as text",
    "body": "Opening `assets/logo.bin` printed replacement characters before the fix.",
    "status": "CLOSED",
    "authorUsername": "forge-viewer",
    "createdAt": "2026-08-21T05:12:44.672480Z",
    "updatedAt": "2026-08-21T05:12:48.899061Z"
  },
  {
    "id": "71f3ccfb-dbb5-4318-b098-b0c663fb2f54",
    "number": 2,
    "title": "Commit graph lanes drift when a branch forks from a commit that the mainline also reaches later, which makes the trunk step sideways instead of running straight",
    "body": "The trunk should stay in one column.\n\n## What happens\n\nWhen a branch forks from a commit that the mainline later reaches, the branch\nreserves that commit's lane first. The mainline then follows it.\n\n| width | trunk lane | expected |\n| ----- | ---------- | -------- |\n| 1440  | 0 then 1   | 0        |\n| 390   | 0 then 1   | 0        |\n\n## Reproduction\n\n1. Fork `feature/cache` from `081f7f3`\n2. Commit on both sides\n3. Open the **Commits** tab\n\n```js\nconst lane = lanes.indexOf(commit.sha);\nif (lane < reserved) lanes[reserved] = null;\n```\n\n- [x] Reproduced at 1440\n- [x] Reproduced at 390\n- [ ] Regression test\n\n> The leftmost claim should win, since it is the longer-running line.\n\nSee `utils/commitGraph.js` and the [commit graph](https://example.test/graph).\n",
    "status": "OPEN",
    "authorUsername": "forge-dev",
    "createdAt": "2026-08-21T05:12:44.245967Z",
    "updatedAt": "2026-08-21T05:12:44.245967Z"
  },
  {
    "id": "0adb5aeb-ce0e-4d98-939b-90e38b126e36",
    "number": 1,
    "title": "Graph gutter drifts on very wide diffs",
    "body": "Seen at 390px.\n\n- [ ] check sticky\n- [ ] check gutters\n",
    "status": "OPEN",
    "authorUsername": "forge-dev",
    "createdAt": "2026-08-21T05:04:01.113715Z",
    "updatedAt": "2026-08-21T05:04:01.353082Z"
  }
];

/** The thread on issue #2, oldest first, including one comment with no author. */
export const commentThread = [
  {
    "id": "ad5bf8e8-965f-427a-8250-f5a02d598fe5",
    "body": "Reproduced. The lane vector reserves the parent before the mainline reaches it.",
    "authorUsername": "forge-viewer",
    "createdAt": "2026-08-21T05:12:46.785671Z",
    "updatedAt": "2026-08-21T05:12:46.785671Z"
  },
  {
    "id": "85f143d4-bef1-430f-99fb-faf98dbde7ab",
    "body": "Fix is in `assignLanes`: when the first parent is already reserved in a **higher** lane, move it to the lower one.\n\n```js\nif (lane < reserved) { lanes[reserved] = null; lanes[lane] = parentSha; }\n```",
    "authorUsername": "forge-dev",
    "createdAt": "2026-08-21T05:12:47.206236Z",
    "updatedAt": "2026-08-21T05:12:47.206236Z"
  },
  {
    "id": "ab8a5976-387e-4e4e-a4d9-13b2af729d88",
    "body": "Confirmed fixed at 1440 and 390. The trunk is a straight ember line now.",
    "authorUsername": "forge-viewer",
    "createdAt": "2026-08-21T05:12:47.630939Z",
    "updatedAt": "2026-08-21T05:12:47.630939Z"
  },
  {
    "id": "898d6ab5-7b6e-44ed-93a6-dd7bc2f0c7b7",
    "body": "Drive-by note: this also affected the boundary stubs.",
    "authorUsername": null,
    "createdAt": "2026-08-21T05:12:48.051426Z",
    "updatedAt": "2026-08-21T05:12:48.051426Z"
  }
];

/** What the server says when someone who is neither author nor owner tries to edit. */
export const forbiddenBody = {
  "timestamp": "2026-08-21T05:13:36.617991231Z",
  "status": 403,
  "code": "FORBIDDEN",
  "message": "Only the issue author or the repository owner may modify it",
  "path": "/api/v1/issues/4a77c7f9-018a-4bd4-8ebe-63f9589eef81"
};
