#!/usr/bin/env python3
"""Ask a vulnerability database what is wrong with what we depend on.

Until now nothing did. The build resolved a hundred-odd Maven artifacts and a
few hundred npm packages and never asked whether any of them had a published
advisory against it, so the only way a known vulnerability would have been
noticed here is if somebody happened to read about it.

Why this rather than a scanner off the shelf
--------------------------------------------
The obvious candidates each brought something this repository should not take
on. OWASP dependency-check needs an NVD API key, and the CI workflow makes a
point of using no secrets so that it runs unchanged on a fork -- a gate that
cannot run on a fork is a gate contributors learn to ignore. The container and
action based scanners are a third-party dependency in the one place a
supply-chain compromise is most useful to an attacker, which is an odd trade to
make in the name of supply-chain security.

What is left is the database all of them consult. OSV.dev has a public API, no
key, no rate limit worth the name at this size, and it aggregates GHSA, the Go
advisory database, RustSec and others behind one schema. Asking it directly is
about a hundred lines, uses nothing but the standard library, and is auditable
in one sitting. The dependency graph itself still comes from the package
managers -- ``mvn dependency:list`` and ``npm audit`` -- because they are the
only things that actually know how a version was resolved.

What it will and will not do
----------------------------
It reports. It does not upgrade anything, and it does not decide that a finding
does not count. Findings that have been assessed and accepted live in
``security/dependency-exceptions.json`` with a reason and a date to look again,
and they are still printed on every run -- listed under ``accepted`` rather than
removed, because an exception nobody sees is indistinguishable from a bug
nobody fixed.

Exit codes: 0 nothing outstanding, 1 findings, 2 the scan itself failed. The
third matters: a scanner that cannot reach the database must not report a clean
build.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from datetime import date, datetime, timezone

OSV_QUERY_BATCH = "https://api.osv.dev/v1/querybatch"
OSV_VULN = "https://api.osv.dev/v1/vulns/"

# Severities in the order they matter. Anything not recognised sorts as UNKNOWN,
# which is deliberately near the top: an advisory whose severity we cannot read
# is not an advisory we may quietly rank last.
SEVERITY_ORDER = ["CRITICAL", "HIGH", "MODERATE", "UNKNOWN", "LOW", "NONE"]

# Maven's dependency:list writes one artifact per line, indented, as
#   group:artifact:type[:classifier]:version:scope
# with an optional trailing " -- module ..." note that must not be parsed.
MAVEN_LINE = re.compile(
    r"^\s+(?P<group>[\w.\-]+):(?P<artifact>[\w.\-]+):(?P<type>\w+):"
    r"(?:(?P<classifier>[\w.\-]+):)?(?P<version>[\w.\-]+):(?P<scope>\w+)"
)


def post_json(url: str, payload: dict) -> dict:
    body = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        url, data=body, headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.loads(response.read().decode("utf-8"))


def get_json(url: str) -> dict:
    with urllib.request.urlopen(url, timeout=60) as response:
        return json.loads(response.read().decode("utf-8"))


def maven_packages(path: str) -> list[dict]:
    """The resolved Maven artifacts, deduplicated, with their scope kept.

    Scope is kept because it is the difference between something that ships and
    something that only ever ran a test. Nothing is dropped on that basis here;
    it is recorded so that the report can say which is which.
    """
    seen: dict[tuple[str, str], dict] = {}
    with open(path, encoding="utf-8", errors="replace") as handle:
        for line in handle:
            match = MAVEN_LINE.match(line.rstrip("\n"))
            if not match:
                continue
            name = f"{match['group']}:{match['artifact']}"
            version = match["version"]
            # Our own modules are not published anywhere and cannot have an
            # advisory against them.
            if name.startswith("com.gitforge:"):
                continue
            seen[(name, version)] = {
                "ecosystem": "Maven",
                "name": name,
                "version": version,
                "scope": match["scope"],
            }
    return sorted(seen.values(), key=lambda p: (p["name"], p["version"]))


def severity_of(vuln: dict) -> str:
    """The worst label the advisory offers, however it chose to express it."""
    specific = vuln.get("database_specific") or {}
    label = str(specific.get("severity") or "").upper()
    if label in SEVERITY_ORDER:
        return label

    best = 0.0
    for entry in vuln.get("severity") or []:
        score = str(entry.get("score") or "")
        # CVSS vectors carry no number, so map by band once parsed by the
        # database; where only a vector is given, fall back to UNKNOWN rather
        # than inventing a score from the vector string.
        try:
            best = max(best, float(score))
        except ValueError:
            continue
    if best >= 9.0:
        return "CRITICAL"
    if best >= 7.0:
        return "HIGH"
    if best >= 4.0:
        return "MODERATE"
    if best > 0:
        return "LOW"
    return "UNKNOWN"


def scan_maven(packages: list[dict]) -> list[dict]:
    """One batched query for every artifact, then details for what came back."""
    if not packages:
        return []
    queries = [
        {"package": {"ecosystem": p["ecosystem"], "name": p["name"]}, "version": p["version"]}
        for p in packages
    ]
    answer = post_json(OSV_QUERY_BATCH, {"queries": queries})
    results = answer.get("results", [])

    findings = []
    details: dict[str, dict] = {}
    for package, result in zip(packages, results):
        for hit in result.get("vulns") or []:
            identifier = hit["id"]
            if identifier not in details:
                details[identifier] = get_json(OSV_VULN + identifier)
            vuln = details[identifier]
            findings.append(
                {
                    "ecosystem": "Maven",
                    "package": package["name"],
                    "version": package["version"],
                    "scope": package["scope"],
                    "id": identifier,
                    "aliases": vuln.get("aliases", []),
                    "severity": severity_of(vuln),
                    "summary": (vuln.get("summary") or "").strip(),
                }
            )
    return findings


def npm_findings(path: str) -> list[dict]:
    """Whatever ``npm audit --json`` said, in the same shape as the rest.

    ``via`` comes in two forms and both have to be followed. An object is the
    advisory itself. A bare string is the name of another package -- "you have
    this because that has it" -- and the advisory ids are on that one. Reading
    only the objects leaves every inherited finding with no identifier, which
    then matches no exception and cannot be assessed at all: the package that is
    actually at fault gets accepted while the one that merely depends on it
    fails the build forever.
    """
    if not path or not os.path.exists(path):
        return []
    with open(path, encoding="utf-8", errors="replace") as handle:
        report = json.load(handle)

    vulnerabilities = report.get("vulnerabilities") or {}

    def advisories(name: str, seen: set[str]) -> tuple[list[str], list[str]]:
        """Advisory ids and titles for a package, following named packages."""
        if name in seen:
            return [], []
        seen.add(name)
        identifiers, titles = [], []
        for via in (vulnerabilities.get(name) or {}).get("via") or []:
            if isinstance(via, dict):
                if via.get("title"):
                    titles.append(via["title"])
                if via.get("url"):
                    identifiers.append(via["url"].rsplit("/", 1)[-1])
            elif isinstance(via, str):
                inherited_ids, inherited_titles = advisories(via, seen)
                identifiers.extend(inherited_ids)
                titles.extend(inherited_titles)
        return identifiers, titles

    findings = []
    for name, entry in sorted(vulnerabilities.items()):
        identifiers, titles = advisories(name, set())
        findings.append(
            {
                "ecosystem": "npm",
                "package": name,
                "version": entry.get("range", "unknown"),
                # npm tells us directly whether we asked for this package or
                # whether something we asked for did.
                "scope": "direct" if entry.get("isDirect") else "transitive",
                "id": identifiers[0] if identifiers else "npm:" + name,
                "aliases": identifiers,
                "severity": str(entry.get("severity", "unknown")).upper(),
                "summary": "; ".join(t for t in titles if t),
            }
        )
    return findings


def load_exceptions(path: str) -> dict[str, dict]:
    """Assessed findings, by advisory id. Never a way to hide one."""
    if not path or not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8") as handle:
        document = json.load(handle)
    return {entry["id"]: entry for entry in document.get("accepted", [])}


def expired(entry: dict) -> bool:
    review = entry.get("review_by")
    if not review:
        return True
    try:
        return date.fromisoformat(review) < date.today()
    except ValueError:
        return True


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--maven-list", help="output of mvn dependency:list")
    parser.add_argument("--npm-audit", help="output of npm audit --json")
    parser.add_argument("--exceptions", default="security/dependency-exceptions.json")
    parser.add_argument("--report", default="dependency-scan-report.json")
    parser.add_argument(
        "--fail-on",
        default="MODERATE",
        choices=SEVERITY_ORDER,
        help="the least severe finding that fails the build",
    )
    args = parser.parse_args()

    try:
        packages = maven_packages(args.maven_list) if args.maven_list else []
        findings = scan_maven(packages) + npm_findings(args.npm_audit)
    except (urllib.error.URLError, OSError, ValueError) as failure:
        # A scan that could not run is not a scan that found nothing.
        print(f"dependency scan could not complete: {failure}", file=sys.stderr)
        return 2

    exceptions = load_exceptions(args.exceptions)
    threshold = SEVERITY_ORDER.index(args.fail_on)

    outstanding, accepted, stale = [], [], []
    for finding in findings:
        matched = exceptions.get(finding["id"]) or next(
            (exceptions[a] for a in finding["aliases"] if a in exceptions), None
        )
        if matched and not expired(matched):
            accepted.append({**finding, "accepted_because": matched.get("because", "")})
            continue
        if matched:
            stale.append({**finding, "expired_review": matched.get("review_by")})
        rank = SEVERITY_ORDER.index(finding["severity"]) \
            if finding["severity"] in SEVERITY_ORDER else SEVERITY_ORDER.index("UNKNOWN")
        if rank <= threshold:
            outstanding.append(finding)

    report = {
        "scanned_at": datetime.now(timezone.utc).isoformat(),
        "source": "https://api.osv.dev + npm audit",
        "maven_packages_scanned": len(packages),
        "fail_on": args.fail_on,
        "outstanding": outstanding,
        "accepted": accepted,
        "expired_exceptions": stale,
    }
    with open(args.report, "w", encoding="utf-8") as handle:
        json.dump(report, handle, indent=2, sort_keys=True)

    print(f"scanned {len(packages)} Maven artifacts and the npm lockfile")
    for finding in accepted:
        print(f"  accepted   {finding['severity']:<9} {finding['ecosystem']:<5} "
              f"{finding['package']} {finding['id']}")
    for finding in stale:
        print(f"  EXPIRED    {finding['severity']:<9} {finding['ecosystem']:<5} "
              f"{finding['package']} {finding['id']} "
              f"(review was due {finding['expired_review']})")
    for finding in outstanding:
        print(f"  FINDING    {finding['severity']:<9} {finding['ecosystem']:<5} "
              f"{finding['package']} {finding['version']} [{finding['scope']}] "
              f"{finding['id']} {finding['summary'][:90]}")

    if outstanding:
        print(f"\n{len(outstanding)} finding(s) at or above {args.fail_on}. "
              f"Full report: {args.report}", file=sys.stderr)
        return 1
    print("no outstanding findings")
    return 0


if __name__ == "__main__":
    sys.exit(main())
