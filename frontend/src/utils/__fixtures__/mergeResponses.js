/**
 * Real merge responses captured from the running engine on forge-dev/merge-demo.
 *
 * Every outcome and every conflict kind, recorded rather than invented - the
 * shape of a conflict's three sides, and which of them are absent, is precisely
 * what a hand-written fixture would get wrong.
 */

/** uptodate-target <- uptodate-source: HTTP 200, ALREADY_UP_TO_DATE */
export const alreadyUpToDate = {
  "outcome": "ALREADY_UP_TO_DATE",
  "head": "a7b277fa52e5ab5128a08a6f9e2efcb2ac10fbb8",
  "mergeCommit": null,
  "tree": null,
  "conflicts": [],
  "cleanlyMerged": []
};

/** ff-target <- ff-source: HTTP 200, ALREADY_UP_TO_DATE */
export const fastForwarded = {
  "outcome": "FAST_FORWARDED",
  "head": "671a28126c240a383038f4ceb3807d14047a640c",
  "mergeCommit": null,
  "tree": null,
  "conflicts": [],
  "cleanlyMerged": []
};

/** clean-target <- clean-source: HTTP 200, ALREADY_UP_TO_DATE */
export const merged = {
  "outcome": "MERGED",
  "head": "4b4de7bf1ae490760386823693275b60b70c1878",
  "mergeCommit": "4b4de7bf1ae490760386823693275b60b70c1878",
  "tree": "129950cc6c3caf11bc14e62a41721def5a93df59",
  "conflicts": [],
  "cleanlyMerged": []
};

/** content-target <- content-source: HTTP 409, CONFLICTED */
export const contentConflict = {
  "outcome": "CONFLICTED",
  "head": null,
  "mergeCommit": null,
  "tree": null,
  "conflicts": [
    {
      "kind": "CONTENT",
      "path": "shared.txt",
      "base": {
        "mode": "100644",
        "id": "85c30401ce288f253613cb07ee32e62128089caa",
        "directory": false
      },
      "ours": {
        "mode": "100644",
        "id": "90eb71e547438e11e26fdfb21fd1471adc17cdcb",
        "directory": false
      },
      "theirs": {
        "mode": "100644",
        "id": "6a9aab3a64461f5a6b366561b2149cc6c3b329d3",
        "directory": false
      }
    }
  ],
  "cleanlyMerged": []
};

/** addadd-target <- addadd-source: HTTP 409, CONFLICTED */
export const addAddConflict = {
  "outcome": "CONFLICTED",
  "head": null,
  "mergeCommit": null,
  "tree": null,
  "conflicts": [
    {
      "kind": "ADD_ADD",
      "path": "fresh.txt",
      "base": null,
      "ours": {
        "mode": "100644",
        "id": "ee3e9a938b19a64f171ecdd54b71149a875e9ac3",
        "directory": false
      },
      "theirs": {
        "mode": "100644",
        "id": "b551a74572d1e463e39fd892676c293908abdd17",
        "directory": false
      }
    }
  ],
  "cleanlyMerged": []
};

/** moddel-target <- moddel-source: HTTP 409, CONFLICTED */
export const modifyDeleteConflict = {
  "outcome": "CONFLICTED",
  "head": null,
  "mergeCommit": null,
  "tree": null,
  "conflicts": [
    {
      "kind": "MODIFY_DELETE",
      "path": "doomed.txt",
      "base": {
        "mode": "100644",
        "id": "198f7c12e8e7725589fd6e7835a6ed56a93b81a4",
        "directory": false
      },
      "ours": {
        "mode": "100644",
        "id": "bd7004876242884861cd854afe6b6f2460b5a6d6",
        "directory": false
      },
      "theirs": null
    }
  ],
  "cleanlyMerged": []
};

/** mode-target <- mode-source: HTTP 409, CONFLICTED */
export const modeConflict = {
  "outcome": "CONFLICTED",
  "head": null,
  "mergeCommit": null,
  "tree": null,
  "conflicts": [
    {
      "kind": "MODE",
      "path": "tool.sh",
      "base": null,
      "ours": {
        "mode": "100755",
        "id": "4163036efa65bd4a469e752267498f01ea36a55c",
        "directory": false
      },
      "theirs": {
        "mode": "100644",
        "id": "4163036efa65bd4a469e752267498f01ea36a55c",
        "directory": false
      }
    }
  ],
  "cleanlyMerged": []
};

/** type-target <- type-source: HTTP 409, CONFLICTED */
export const typeConflict = {
  "outcome": "CONFLICTED",
  "head": null,
  "mergeCommit": null,
  "tree": null,
  "conflicts": [
    {
      "kind": "TYPE",
      "path": "thing",
      "base": {
        "mode": "100644",
        "id": "b276fa816f03334eec889f0cb0240784ba5dccc6",
        "directory": false
      },
      "ours": {
        "mode": "100644",
        "id": "7acd9ff87f20bddc6f87134e33aa1e149bedb95f",
        "directory": false
      },
      "theirs": {
        "mode": "40000",
        "id": "bb75813d7a5d8b37f9ae842ed823bbb5116d5a6b",
        "directory": true
      }
    }
  ],
  "cleanlyMerged": []
};
