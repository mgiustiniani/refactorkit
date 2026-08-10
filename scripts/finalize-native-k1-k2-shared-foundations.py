#!/usr/bin/env python3
"""Finalize one bounded native K1/K2/shared-foundations qualification receipt."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform as host_platform
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

PLATFORMS = {"linux-x86_64", "windows-x86_64", "macos-x86_64", "macos-aarch64"}
REQUIRED_SUITES = {
    "org.refactorkit.core.BuildModelsTest",
    "org.refactorkit.core.WorkspaceRefreshCoordinatorTest",
    "org.refactorkit.java.JdtJavaAnalysisCacheTest",
    "org.refactorkit.java.JdtJavaSemanticAnalyzerTest",
    "org.refactorkit.kotlin.KotlinCompilerAnalysisSessionTest",
    "org.refactorkit.kotlin.KotlinCompilerDiagnosticsTest",
    "org.refactorkit.kotlin.KotlinJvmBuildModelTest",
    "org.refactorkit.kotlin.KotlinLanguageAdapterTest",
    "org.refactorkit.jvm.SharedJvmIdentityIndexTest",
    "org.refactorkit.daemon.WorkspaceIndexDaemonTest",
    "org.refactorkit.daemon.DaemonSessionTest",
    "org.refactorkit.mcp.McpSessionTest",
    "org.refactorkit.lsp.LspSessionTest",
    "org.refactorkit.typescript.TypeScriptBuildModelProviderTest",
}
REQUIRED_JARS = {
    f"refactorkit-{module}-0.7.0-SNAPSHOT.jar"
    for module in ("core", "java", "kotlin", "jvm", "daemon", "mcp", "lsp")
}
SMOKE_MARKER = (
    "Packaged Kotlin acceptance passed: descriptor-exact K2 declaration/constructor reads, "
    "private rename, bidirectional public type/member rename, shared Java/Kotlin add-parameter, "
    "and public Kotlin package move apply/rollback."
)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def detected_platform() -> str:
    system = host_platform.system().lower()
    system_name = {"linux": "linux", "windows": "windows", "darwin": "macos"}.get(system)
    machine = host_platform.machine().lower()
    machine_name = (
        "x86_64" if machine in {"x86_64", "amd64"}
        else "aarch64" if machine in {"aarch64", "arm64"}
        else None
    )
    if system_name is None or machine_name is None:
        raise ValueError(f"unsupported qualification host: {system}/{machine}")
    return f"{system_name}-{machine_name}"


def parse_count(value: str | None, field: str, path: Path) -> int:
    try:
        result = int(value or "")
    except ValueError as problem:
        raise ValueError(f"invalid JUnit {field} in {path}") from problem
    if result < 0:
        raise ValueError(f"negative JUnit {field} in {path}")
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--platform", required=True)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--support-receipt", type=Path, required=True)
    parser.add_argument("--smoke-log", type=Path, required=True)
    parser.add_argument("--report-root", type=Path, required=True)
    parser.add_argument("--package-root", type=Path, required=True)
    parser.add_argument("--repository-root", type=Path, default=Path("."))
    parser.add_argument("--support-projection", type=Path,
                        default=Path("docs/releases/v0.7.0-kotlin-support.json"))
    parser.add_argument("--output", type=Path, required=True)
    options = parser.parse_args()
    repository_root = options.repository_root.resolve()
    support_projection = (options.support_projection if options.support_projection.is_absolute()
                          else repository_root / options.support_projection).resolve()
    if repository_root not in support_projection.parents or not support_projection.is_file():
        raise ValueError("support projection must be a regular file inside the subject repository")

    if options.platform not in PLATFORMS:
        raise ValueError(f"unsupported qualification platform: {options.platform}")
    actual_platform = detected_platform()
    if options.platform != actual_platform:
        raise ValueError(
            f"qualification platform mismatch: declared {options.platform}, host is {actual_platform}"
        )
    if not re.fullmatch(r"[0-9a-f]{40}", options.revision):
        raise ValueError("revision must be an exact 40-character lowercase Git SHA")
    try:
        current_revision = subprocess.run(
            ["git", "-C", str(repository_root), "rev-parse", "HEAD"],
            check=True, text=True, capture_output=True,
        ).stdout.strip()
    except Exception as problem:
        raise ValueError("cannot resolve current Git revision") from problem
    if current_revision != options.revision:
        raise ValueError(f"revision mismatch: expected {options.revision}, checkout is {current_revision}")
    tracked_status = subprocess.run(
        ["git", "-C", str(repository_root), "status", "--porcelain", "--untracked-files=no"],
        check=True, text=True, capture_output=True,
    ).stdout.strip()
    if tracked_status:
        raise ValueError("tracked checkout is dirty; native receipt cannot bind an uncommitted subject")
    subprocess.run(
        ["git", "-C", str(repository_root), "diff", "--check", "HEAD"],
        check=True, text=True, capture_output=True,
    )

    support = json.loads(options.support_receipt.read_text(encoding="utf-8"))
    if support.get("status") != "PASSED" or support.get("failures") != []:
        raise ValueError("Kotlin support reconciliation receipt is missing or non-passing")
    projection_hash = sha256(support_projection)
    if support.get("supportProjectionSha256") != projection_hash:
        raise ValueError("support projection hash does not match its reconciliation receipt")

    smoke_text = options.smoke_log.read_text(encoding="utf-8")
    if SMOKE_MARKER not in smoke_text or "::error" in smoke_text:
        raise ValueError("packaged Kotlin smoke log is missing its exact passing marker")

    reports = sorted(options.report_root.glob("*/build/test-results/test/TEST-*.xml"))
    if not reports:
        raise ValueError("no focused JUnit XML reports were found")
    suite_counts: dict[str, dict[str, object]] = {}
    required_counts: dict[str, dict[str, int]] = {}
    report_hashes: dict[str, str] = {}
    unexpected_reports: list[str] = []
    totals = {"tests": 0, "skipped": 0, "failures": 0, "errors": 0, "executed": 0}
    for report in reports:
        root = ET.parse(report).getroot()
        name = root.attrib.get("name", "")
        counts = {
            field: parse_count(root.attrib.get(field), field, report)
            for field in ("tests", "skipped", "failures", "errors")
        }
        counts["executed"] = counts["tests"] - counts["skipped"]
        if counts["executed"] < 0:
            raise ValueError(f"JUnit skipped count exceeds tests in {report}")
        report_key = str(report.as_posix())
        suite_counts[report_key] = {"name": name, **counts}
        if name not in REQUIRED_SUITES:
            unexpected_reports.append(report_key)
        else:
            if name in required_counts:
                raise ValueError(f"duplicate required JUnit suite: {name}")
            required_counts[name] = counts
        for field in totals:
            totals[field] += counts[field]
        report_hashes[report_key] = sha256(report)
    if unexpected_reports:
        raise ValueError(f"unexpected non-focused JUnit reports are present: {unexpected_reports}")
    missing = sorted(REQUIRED_SUITES - required_counts.keys())
    if missing:
        raise ValueError(f"required focused JUnit suites are missing: {missing}")
    if totals["failures"] or totals["errors"] or totals["executed"] <= 0:
        raise ValueError(f"focused JUnit aggregate is not passing: {totals}")
    empty_required = sorted(name for name in REQUIRED_SUITES if required_counts[name]["executed"] <= 0)
    if empty_required:
        raise ValueError(f"required JUnit suites have no executed tests: {empty_required}")

    package_root = options.package_root.resolve()
    java = package_root / "runtime/bin" / ("java.exe" if os.name == "nt" else "java")
    launcher = package_root / "bin" / ("refactorkit.bat" if os.name == "nt" else "refactorkit")
    if not java.is_file() or not launcher.is_file():
        raise ValueError("packaged runtime Java or CLI launcher is missing")
    java_result = subprocess.run([str(java), "-version"], text=True, capture_output=True, check=True)
    java_version = (java_result.stderr + java_result.stdout).strip()
    if (
        re.search(r'^openjdk version "21\.0\.11"', java_version, re.MULTILINE) is None
        or re.search(r'\bbuild 21\.0\.11\+10(?:-LTS)?\b', java_version) is None
    ):
        raise ValueError(f"embedded runtime is not exact qualified JDK 21.0.11+10: {java_version}")
    library = package_root / "lib"
    jar_paths = {path.name: path for path in library.glob("refactorkit-*-0.7.0-SNAPSHOT.jar")}
    missing_jars = sorted(REQUIRED_JARS - jar_paths.keys())
    if missing_jars:
        raise ValueError(f"required packaged subject jars are missing: {missing_jars}")

    receipt = {
        "schemaVersion": 1,
        "status": "PASSED",
        "scope": "v0.7.0-k1-k2-shared-foundations",
        "revision": options.revision,
        "platform": options.platform,
        "detectedPlatform": actual_platform,
        "host": {
            "system": host_platform.system(),
            "machine": host_platform.machine(),
        },
        "embeddedRuntime": {
            "versionOutput": java_version,
            "javaSha256": sha256(java),
        },
        "support": {
            "projectionSha256": projection_hash,
            "receiptSha256": sha256(options.support_receipt),
            "licenseRows": support.get("licenseRows"),
            "sbomState": support.get("sbomState"),
        },
        "packagedSmokeSha256": sha256(options.smoke_log),
        "junit": {
            "requiredSuites": sorted(REQUIRED_SUITES),
            "suiteCounts": {name: suite_counts[name] for name in sorted(suite_counts)},
            "totals": totals,
            "reportSha256": dict(sorted(report_hashes.items())),
        },
        "packagedSubjectJarSha256": {
            name: sha256(jar_paths[name]) for name in sorted(REQUIRED_JARS)
        },
        "boundaries": [
            "Operation-specific Kotlin/JVM candidate rows only; no general managed-support claim.",
            "Multiplatform, Android, scripts, compiler plugins, generated mutation, and expect/actual remain refused.",
            "Configured-unpublished SPDX generation is not a final release SBOM or publication claim.",
            "This dedicated native receipt is not a green whole-repository CI claim.",
        ],
    }
    options.output.parent.mkdir(parents=True, exist_ok=True)
    options.output.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(receipt, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as problem:
        print(f"qualification finalization failed: {problem}", file=sys.stderr)
        sys.exit(1)
