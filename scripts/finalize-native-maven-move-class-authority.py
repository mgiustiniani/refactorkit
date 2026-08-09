#!/usr/bin/env python3
"""Create a fail-closed, revision-bound native Maven move-class receipt."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform as host_platform
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ALLOWED_PLATFORMS = {
    "linux-x86_64",
    "windows-x86_64",
    "macos-x86_64",
    "macos-aarch64",
}
EXPECTED_BASELINE_SHA256 = "dfab8db43830383aa771b0ecead5394e1525a7f16732dfc1f19acac8261878d6"
EXPECTED_FEATURE_SHA256 = "3c42982c9d792c78162b680ff195fab361551f7392574f43c7247812bcb50696"
EXPECTED_TOTALS = {"requirements": 13, "scenarios": 31, "steps": 436, "hooks": 62}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def cucumber_summary(path: Path) -> dict[str, object]:
    document = json.loads(path.read_text(encoding="utf-8"))
    scenarios = [
        element
        for feature in document
        for element in feature.get("elements", [])
        if element.get("type") in {"scenario", "scenario_outline"}
    ]
    statuses: list[str] = []
    for scenario in scenarios:
        for section in ("before", "steps", "after"):
            statuses.extend(
                item.get("result", {}).get("status", "missing")
                for item in scenario.get(section, [])
            )
    if len(scenarios) != 1 or not statuses or any(status != "passed" for status in statuses):
        raise ValueError("public-CLI Cucumber receipt is missing or non-passing")
    return {
        "path": path.name,
        "sha256": sha256(path),
        "scenarios": len(scenarios),
        "results": len(statuses),
        "status": "PASSED",
    }


def junit_summary(directory: Path) -> dict[str, object]:
    paths = sorted(directory.glob("TEST-*.xml"))
    if not paths:
        raise FileNotFoundError(f"no JUnit receipts under {directory}")
    totals = {"tests": 0, "skipped": 0, "failures": 0, "errors": 0}
    hashes: list[dict[str, str]] = []
    for path in paths:
        root = ET.parse(path).getroot()
        for field in totals:
            totals[field] += int(root.attrib.get(field, "0"))
        hashes.append({"path": path.name, "sha256": sha256(path)})
    executed = totals["tests"] - totals["skipped"]
    if totals != {"tests": 31, "skipped": 30, "failures": 0, "errors": 0} or executed != 1:
        raise ValueError(f"unexpected public-CLI JUnit totals: {totals}, executed={executed}")
    return {**totals, "executed": executed, "files": hashes}


def git(repository: Path, *arguments: str) -> str:
    return subprocess.run(
        ["git", *arguments],
        cwd=repository,
        check=True,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    ).stdout.strip()


def finalize(repository: Path, build_root: Path, platform: str, expected_revision: str) -> dict[str, object]:
    if platform not in ALLOWED_PLATFORMS:
        raise ValueError(f"unsupported platform identity: {platform}")
    if len(expected_revision) != 40 or any(character not in "0123456789abcdef" for character in expected_revision):
        raise ValueError("expected revision must be one lowercase 40-character Git SHA")
    observed_revision = git(repository, "rev-parse", "HEAD")
    if observed_revision != expected_revision:
        raise ValueError(f"revision mismatch: expected {expected_revision}, observed {observed_revision}")
    if git(repository, "status", "--porcelain=v1", "--untracked-files=no"):
        raise ValueError("tracked checkout changed during qualification")

    baseline = repository / "docs/requirements/req-java-maven-move-auth-013-baseline.md"
    feature = repository / "features/java-maven-move-class-apply-authority.feature"
    if sha256(baseline) != EXPECTED_BASELINE_SHA256:
        raise ValueError("REQ-013 baseline identity drifted")
    if sha256(feature) != EXPECTED_FEATURE_SHA256:
        raise ValueError("move-class feature identity drifted")

    report_root = build_root / "reports/cucumber"
    public_cucumber = cucumber_summary(report_root / "packaged-maven-move-class-authority.json")
    public_junit = junit_summary(
        build_root / "test-results/packagedMavenMoveClassAuthorityTest"
    )
    matrix_path = (
        build_root
        / "qualification/packaged-maven-move-class-authority/matrix-manifest.json"
    )
    matrix = json.loads(matrix_path.read_text(encoding="utf-8"))
    if matrix.get("status") != "PASSED" or matrix.get("totals") != EXPECTED_TOTALS:
        raise ValueError("complete packaged matrix is missing, failed, or has wrong totals")
    if matrix.get("featureSha256") != EXPECTED_FEATURE_SHA256:
        raise ValueError("complete packaged matrix used the wrong feature identity")
    if matrix.get("req013BaselineSha256") != EXPECTED_BASELINE_SHA256:
        raise ValueError("complete packaged matrix used the wrong REQ-013 baseline")

    runtime_release = build_root / "package/refactorkit/runtime/release"
    if not runtime_release.is_file():
        raise FileNotFoundError(f"embedded runtime release file missing: {runtime_release}")
    java_version = subprocess.run(
        [str(build_root / "package/refactorkit/runtime/bin" / ("java.exe" if os.name == "nt" else "java")), "-version"],
        check=True,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )

    return {
        "schema": "refactorkit.native-maven-move-class-authority/v1",
        "status": "PASSED",
        "sourceRevision": observed_revision,
        "platform": platform,
        "host": {
            "system": host_platform.system(),
            "machine": host_platform.machine(),
        },
        "ci": {
            "repository": os.environ.get("GITHUB_REPOSITORY", "local"),
            "runId": os.environ.get("GITHUB_RUN_ID", "local"),
            "runAttempt": os.environ.get("GITHUB_RUN_ATTEMPT", "local"),
        },
        "jdk": {
            "setupJavaIdentity": "temurin/21.0.11+10.0.LTS",
            "embeddedReleaseSha256": sha256(runtime_release),
            "embeddedVersionOutput": (java_version.stdout + java_version.stderr).strip().splitlines(),
        },
        "baselineSha256": EXPECTED_BASELINE_SHA256,
        "featureSha256": EXPECTED_FEATURE_SHA256,
        "publicCli": {
            "cucumber": public_cucumber,
            "junit": public_junit,
        },
        "completeMatrix": {
            "path": matrix_path.name,
            "sha256": sha256(matrix_path),
            "totals": matrix["totals"],
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository-root", type=Path, required=True)
    parser.add_argument("--build-root", type=Path, required=True)
    parser.add_argument("--platform", required=True)
    parser.add_argument("--expected-revision", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        receipt = finalize(
            args.repository_root.resolve(),
            args.build_root.resolve(),
            args.platform,
            args.expected_revision,
        )
        exit_code = 0
    except Exception as failure:  # fail-closed receipt is intentional
        receipt = {
            "schema": "refactorkit.native-maven-move-class-authority/v1",
            "status": "FAILED",
            "platform": args.platform,
            "sourceRevision": args.expected_revision,
            "failure": f"{type(failure).__name__}: {failure}",
        }
        exit_code = 1
    rendered = json.dumps(receipt, indent=2, sort_keys=True) + "\n"
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(rendered, encoding="utf-8")
    sys.stdout.write(rendered)
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
