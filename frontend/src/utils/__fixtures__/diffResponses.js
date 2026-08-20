/**
 * Real diff payloads captured from the running engine on forge-dev/diff-demo.
 *
 * Recorded rather than written by hand, because the three states that arrive as
 * "a file with no hunks" - binary, too large, and a mode-only change - are
 * exactly the ones an invented fixture would get subtly wrong.
 */

/** 15b31ac: Rework the parser, drop the doomed file */
export const mixedChangesDiff = {
  "base": null,
  "head": "15b31aca191cda9d1df73b4507d5276213d45061",
  "filesChanged": 4,
  "totalAdditions": 10,
  "totalDeletions": 6,
  "files": [
    {
      "path": "README.md",
      "status": "MODIFIED",
      "oldBlob": "79e6afc1c27da283509216cf18ec784b97d2eb93",
      "newBlob": "b9d3bfc34e8c2915fb471060786f24888c2b40f8",
      "oldMode": "100644",
      "newMode": "100644",
      "binary": false,
      "tooLarge": false,
      "additions": 3,
      "deletions": 1,
      "hunks": [
        {
          "header": "@@ -2,4 +2,6 @@",
          "oldStart": 2,
          "oldCount": 4,
          "newStart": 2,
          "newCount": 6,
          "lines": [
            {
              "type": "CONTEXT",
              "oldNumber": 2,
              "newNumber": 2,
              "content": ""
            },
            {
              "type": "CONTEXT",
              "oldNumber": 3,
              "newNumber": 3,
              "content": "A repository of diff shapes."
            },
            {
              "type": "CONTEXT",
              "oldNumber": 4,
              "newNumber": 4,
              "content": ""
            },
            {
              "type": "REMOVED",
              "oldNumber": 5,
              "newNumber": null,
              "content": "Second paragraph."
            },
            {
              "type": "ADDED",
              "oldNumber": null,
              "newNumber": 5,
              "content": "Second paragraph, revised."
            },
            {
              "type": "ADDED",
              "oldNumber": null,
              "newNumber": 6,
              "content": ""
            },
            {
              "type": "ADDED",
              "oldNumber": null,
              "newNumber": 7,
              "content": "A third paragraph."
            }
          ]
        }
      ]
    },
    {
      "path": "doomed.txt",
      "status": "DELETED",
      "oldBlob": "70a6db373e4b29983d958124a9447a1fcdc07257",
      "newBlob": null,
      "oldMode": "100644",
      "newMode": null,
      "binary": false,
      "tooLarge": false,
      "additions": 0,
      "deletions": 2,
      "hunks": [
        {
          "header": "@@ -1,2 +0,0 @@",
          "oldStart": 1,
          "oldCount": 2,
          "newStart": 0,
          "newCount": 0,
          "lines": [
            {
              "type": "REMOVED",
              "oldNumber": 1,
              "newNumber": null,
              "content": "this file will be deleted"
            },
            {
              "type": "REMOVED",
              "oldNumber": 2,
              "newNumber": null,
              "content": "second line"
            }
          ]
        }
      ]
    },
    {
      "path": "src/parser.txt",
      "status": "MODIFIED",
      "oldBlob": "c4352f8b46de5cdb88d0cc96958316db42dd2398",
      "newBlob": "811667977416f9d69c7d708c49ec4a5577b04fbe",
      "oldMode": "100644",
      "newMode": "100644",
      "binary": false,
      "tooLarge": false,
      "additions": 4,
      "deletions": 3,
      "hunks": [
        {
          "header": "@@ -1,15 +1,15 @@",
          "oldStart": 1,
          "oldCount": 15,
          "newStart": 1,
          "newCount": 15,
          "lines": [
            {
              "type": "CONTEXT",
              "oldNumber": 1,
              "newNumber": 1,
              "content": "line 1"
            },
            {
              "type": "CONTEXT",
              "oldNumber": 2,
              "newNumber": 2,
              "content": "line 2"
            },
            {
              "type": "REMOVED",
              "oldNumber": 3,
              "newNumber": null,
              "content": "line 3"
            },
            {
              "type": "ADDED",
              "oldNumber": null,
              "newNumber": 3,
              "content": "line 3 CHANGED"
            },
            {
              "type": "CONTEXT",
              "oldNumber": 4,
              "newNumber": 4,
              "content": "line 4"
            },
            {
              "type": "CONTEXT",
              "oldNumber": 5,
              "newNumber": 5,
              "content": "line 5"
            },
            {
              "type": "CONTEXT",
              "oldNumber": 6,
              "newNumber": 6,
              "content": "line 6"
            },
            {
              "type": "REMOVED",
              "oldNumber": 7,
              "newNumber": null,
              "content": "line 7"
            },
            {
              "type": "ADDED",
              "oldNumber": null,
              "newNumber": 7,
              "content": "line 7 CHANGED"
            },
            {
              "type": "CONTEXT",
              "oldNumber": 8,
              "newNumber": 8,
              "content": "line 8"
            },
            {
              "type": "CONTEXT",
              "oldNumber": 9,
              "newNumber": 9,
              "content": "line 9"
            },
            {
              "type": "CONTEXT",
              "oldNumber": 10,
              "newNumber": 10,
              "content": "line 10"
            },
            {
              "type": "CONTEXT",
              "oldNumber": 11,
              "newNumber": 11,
              "content": "line 11"
            },
            {
              "type": "REMOVED",
              "oldNumber": 12,
              "newNumber": null,
              "content": "line 12"
            },
            {
              "type": "ADDED",
              "oldNumber": null,
              "newNumber": 12,
              "content": "line 12 CHANGED"
            },
            {
              "type": "CONTEXT",
              "oldNumber": 13,
              "newNumber": 13,
              "content": "line 13"
            },
            {
              "type": "CONTEXT",
              "oldNumber": 14,
              "newNumber": 14,
              "content": "line 14"
            },
            {
              "type": "CONTEXT",
              "oldNumber": 15,
              "newNumber": 15,
              "content": "line 15"
            }
          ]
        },
        {
          "header": "@@ -18,3 +18,4 @@",
          "oldStart": 18,
          "oldCount": 3,
          "newStart": 18,
          "newCount": 4,
          "lines": [
            {
              "type": "CONTEXT",
              "oldNumber": 18,
              "newNumber": 18,
              "content": "line 18"
            },
            {
              "type": "CONTEXT",
              "oldNumber": 19,
              "newNumber": 19,
              "content": "line 19"
            },
            {
              "type": "CONTEXT",
              "oldNumber": 20,
              "newNumber": 20,
              "content": "line 20"
            },
            {
              "type": "ADDED",
              "oldNumber": null,
              "newNumber": 21,
              "content": "line 21 appended"
            }
          ]
        }
      ]
    },
    {
      "path": "wide.txt",
      "status": "ADDED",
      "oldBlob": null,
      "newBlob": "0d3cae3cf9398402744891f925a7031c5378dabe",
      "oldMode": null,
      "newMode": "100644",
      "binary": false,
      "tooLarge": false,
      "additions": 3,
      "deletions": 0,
      "hunks": [
        {
          "header": "@@ -0,0 +1,3 @@",
          "oldStart": 0,
          "oldCount": 0,
          "newStart": 1,
          "newCount": 3,
          "lines": [
            {
              "type": "ADDED",
              "oldNumber": null,
              "newNumber": 1,
              "content": "short line"
            },
            {
              "type": "ADDED",
              "oldNumber": null,
              "newNumber": 2,
              "content": "the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog the quick brown fox jumps over the lazy dog"
            },
            {
              "type": "ADDED",
              "oldNumber": null,
              "newNumber": 3,
              "content": "another short line"
            }
          ]
        }
      ]
    }
  ]
};

/** cf1edd7: Make the run script executable */
export const modeOnlyDiff = {
  "base": null,
  "head": "cf1edd78c8c42179733a79a8b66929d038f31adf",
  "filesChanged": 1,
  "totalAdditions": 0,
  "totalDeletions": 0,
  "files": [
    {
      "path": "scripts/run.sh",
      "status": "MODIFIED",
      "oldBlob": "60d5d278212029ebfb52aff9c825001c4569e118",
      "newBlob": "60d5d278212029ebfb52aff9c825001c4569e118",
      "oldMode": "100644",
      "newMode": "100755",
      "binary": false,
      "tooLarge": false,
      "additions": 0,
      "deletions": 0,
      "hunks": []
    }
  ]
};

/** 8dfffe6: Add the binary logo */
export const binaryDiff = {
  "base": null,
  "head": "8dfffe685549e133c9cbd696f9f9937af316cd66",
  "filesChanged": 1,
  "totalAdditions": 0,
  "totalDeletions": 0,
  "files": [
    {
      "path": "assets/logo.bin",
      "status": "ADDED",
      "oldBlob": null,
      "newBlob": "849da3e03e0fabaec81919637ddbc4b7d7eebaba",
      "oldMode": null,
      "newMode": "100644",
      "binary": true,
      "tooLarge": false,
      "additions": 0,
      "deletions": 0,
      "hunks": []
    }
  ]
};

/** 659dae2: Regenerate the table */
export const tooLargeDiff = {
  "base": null,
  "head": "659dae2c8ab95ff97a0a1a23eb7def85fb24a007",
  "filesChanged": 1,
  "totalAdditions": 0,
  "totalDeletions": 0,
  "files": [
    {
      "path": "generated/table.txt",
      "status": "MODIFIED",
      "oldBlob": "ec43df5c1012ccd090a214f21d25efea58807344",
      "newBlob": "f7225f1a8c5d550b35d0e30dc1e29d19bcf33e44",
      "oldMode": "100644",
      "newMode": "100644",
      "binary": false,
      "tooLarge": true,
      "additions": 0,
      "deletions": 0,
      "hunks": []
    }
  ]
};

/** base and head the same revision: a real, empty diff. */
export const emptyDiff = {
  "base": "main",
  "head": "main",
  "filesChanged": 0,
  "totalAdditions": 0,
  "totalDeletions": 0,
  "files": []
};

/** A real merge commit's detail: two parents, changes against the first. */
export const mergeCommitDetail = {
  "commit": {
    "sha": "bdc35ca7abb9387c418ae1fb24fe23b8dd765ba9",
    "shortSha": "bdc35ca",
    "message": "Merge feature/cache into main\n",
    "authorName": "forge-dev",
    "authorEmail": "forge-dev@example.test",
    "timestamp": "2026-08-20T15:28:01Z",
    "parents": [
      "45f76afffd9c8cd2232e31ccdf442fb1d9d7080a",
      "660a727e4c3493bbe80b636487dd541d2361e7c1"
    ],
    "tree": "446f43b283595c489c135d261c738ad534b60e05",
    "merge": true
  },
  "changes": {
    "changes": [
      {
        "type": "ADDED",
        "path": "cache.txt",
        "oldBlob": null,
        "newBlob": "d0aaf976aff18cfd95e5d6b20de694185239a40d",
        "oldMode": null,
        "newMode": "100644"
      }
    ],
    "added": 1,
    "deleted": 0,
    "modified": 0
  }
};
