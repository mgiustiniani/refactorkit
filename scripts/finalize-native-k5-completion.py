#!/usr/bin/env python3
"""Finalize one fail-closed native receipt for the bounded non-move Kotlin K5 gate."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePath
import platform as host_platform
import re
import subprocess
import xml.etree.ElementTree as ET


PLATFORMS = {"linux-x86_64", "windows-x86_64", "macos-x86_64", "macos-aarch64"}
REQUIREMENT = Path("docs/requirements/kotlin-k5-bounded-completion.md")
REQUIREMENT_SHA256 = "e09f270c9e4daffd578b04e7444cad6ec9658af722734867a2ad01f2f6b44f42"
BOUND_REQUIREMENTS = {
    Path("docs/requirements/kotlin-jvm-organize-imports-callables-formatting.md"):
        "0d28daa17f8d1a92097503125b405775d1ff2ff73361e1674b57643bec3c9a6e",
    Path("docs/requirements/kotlin-jvm-change-signature-parameter-rename.md"):
        "a605ea53b029cbbc003c455419bf99cbc720c5c3df7411f9e6585dde36260e95",
    Path("docs/requirements/kotlin-jvm-bounded-extract-inline.md"):
        "16386a71f2eb579f5f126664f53fba25a775a87f0e680e86ad102146a4b51b77",
    Path("docs/requirements/kotlin-advanced-shapes-and-platform-matrices.md"):
        "5d5960b1e0f90a1f2502b48114179a1a289cc7c09ee144fa4e1933970c5b0238",
}
SMOKE_MARKER = (
    "Packaged K5 completion acceptance passed: callable imports, parameter rename, "
    "bounded extract/inline, advanced-shape inventory, and refusal matrices."
)
REQUIRED_SUITES = {
    "org.refactorkit.kotlin.KotlinCompilerDiagnosticsTest": {
        "organizeImportsRefusesCommentAttachedToImportBlock()",
        "organizeImportsRemovesCompilerProvenUnusedTypeAndSortsCrLfBlock()",
        "organizeImportsUsesCounterfactualK2EvidenceForExternalCallables()",
        "organizeImportsRefusesCompilingCallableBindingSubstitution()",
        "organizeImportsUsesSnapshotBoundEditorConfigLayoutForSourceCallables()",
        "organizeImportsRefusesStaleOrUnsupportedProjectStyleWithoutEdits()",
        "overrideFamiliesAreExactAndExcludeSameSignatureUnrelatedMethods()",
        "namedArgumentsResolveToExactOverloadParameterSymbols()",
        "changeSignatureRefusesCompilerProvenExternalOverrideBoundary()",
        "compilerModelsAdvancedKotlinJvmShapesAndRefusesDelegatedPropertiesExplicitly()",
        "boundedExtractAndInlineUseExactCompilerExpressionRangesAndRollback()",
        "extractAndInlineRefuseUnprovenControlAndUsageShapesWithoutEdits()",
    },
    "org.refactorkit.jvm.KotlinJavaPublicTypeRenamePlannerTest": {
        "kotlinParameterRenameUpdatesOverrideNamedArgumentsAndPreservesJavaCaller()",
        "publicKotlinParameterRenameRequiresExternalConsumerApproval()",
    },
    "org.refactorkit.jvm.KotlinMoveDeclarationDiagnosticsRouteTest": {
        "kotlinChangeSignatureUsesLazyMixedOperationDiagnostics()",
    },
    "org.refactorkit.kotlin.KotlinLanguageAdapterTest": {
        "descriptorPromotesBoundedCompilerReadsAndPrivateTypeRenameProposalOnly()",
    },
    "org.refactorkit.kotlin.KotlinJvmBuildModelTest": {
        "androidAndCompilerPluginFacetsRemainSeparateFailClosedCapabilityCases()",
        "unsupportedKotlinPlatformFailsClosed()",
    },
}
SUBJECT_FILES = {
    Path(".github/workflows/ci.yml"),
    REQUIREMENT,
    REQUIREMENT.with_suffix(REQUIREMENT.suffix + ".sha256"),
    *BOUND_REQUIREMENTS.keys(),
    Path("docs/requirements/java-cli-command-catalog-k5-capability-projection-change.md"),
    Path("docs/requirements/kotlin-cli-change-signature-mode-compatibility.md"),
    Path("docs/requirements/managed-apply-diagnostics-gate-selector-k5-change.md"),
    Path("docs/requirements/managed-apply-diagnostics-gate-selector-k5-change-signature-change.md"),
    Path("docs/kotlin-adapter.md"),
    Path("docs/releases/v0.7.0-k5-bounded-completion-acceptance.md"),
    Path("docs/releases/v0.7.0-support-matrix.md"),
    Path("features/java-cli-command-catalog.feature"),
    Path("features/managed-apply-diagnostics-gate-selector.feature"),
    Path("modules/refactorkit-java/src/main/kotlin/org/refactorkit/java/JavaProjectScanner.kt"),
    Path("modules/refactorkit-kotlin/src/main/java/org/refactorkit/kotlin/bridge/KotlinCompilerBridgeMain.java"),
    Path("modules/refactorkit-kotlin/src/main/java/org/refactorkit/kotlin/bridge/KotlinCompilerCallableSignatureExtractor.java"),
    Path("modules/refactorkit-kotlin/src/main/java/org/refactorkit/kotlin/bridge/KotlinCompilerSymbolExtractor.java"),
    Path("modules/refactorkit-kotlin/src/main/java/org/refactorkit/kotlin/bridge/KotlinCompilerUsageExtractor.java"),
    Path("modules/refactorkit-kotlin/src/main/kotlin/org/refactorkit/kotlin/KotlinChangeSignaturePlanner.kt"),
    Path("modules/refactorkit-kotlin/src/main/kotlin/org/refactorkit/kotlin/KotlinCompilerDiagnostics.kt"),
    Path("modules/refactorkit-kotlin/src/main/kotlin/org/refactorkit/kotlin/KotlinExtractMethodPlanner.kt"),
    Path("modules/refactorkit-kotlin/src/main/kotlin/org/refactorkit/kotlin/KotlinInlineMethodPlanner.kt"),
    Path("modules/refactorkit-kotlin/src/main/kotlin/org/refactorkit/kotlin/KotlinLanguageAdapter.kt"),
    Path("modules/refactorkit-kotlin/src/main/kotlin/org/refactorkit/kotlin/KotlinOrganizeImportsPlanner.kt"),
    Path("modules/refactorkit-kotlin/src/test/kotlin/org/refactorkit/kotlin/KotlinCompilerDiagnosticsTest.kt"),
    Path("modules/refactorkit-kotlin/src/test/kotlin/org/refactorkit/kotlin/KotlinJvmBuildModelTest.kt"),
    Path("modules/refactorkit-kotlin/src/test/kotlin/org/refactorkit/kotlin/KotlinLanguageAdapterTest.kt"),
    Path("modules/refactorkit-jvm/src/main/kotlin/org/refactorkit/jvm/KotlinJvmChangeSignaturePlanner.kt"),
    Path("modules/refactorkit-jvm/src/main/kotlin/org/refactorkit/jvm/ManagedApplyDiagnosticsGateSelector.kt"),
    Path("modules/refactorkit-jvm/src/test/kotlin/org/refactorkit/jvm/KotlinJavaPublicTypeRenamePlannerTest.kt"),
    Path("modules/refactorkit-jvm/src/test/kotlin/org/refactorkit/jvm/KotlinMoveDeclarationDiagnosticsRouteTest.kt"),
    Path("modules/refactorkit-jvm/src/test/kotlin/org/refactorkit/jvm/manageddiagnostics/ManagedApplyDiagnosticsGateSelectorSteps.kt"),
    Path("modules/refactorkit-cli/src/main/kotlin/org/refactorkit/cli/RefactorKitCli.kt"),
    Path("modules/refactorkit-daemon/src/main/kotlin/org/refactorkit/daemon/DaemonSession.kt"),
    Path("modules/refactorkit-mcp/src/main/kotlin/org/refactorkit/mcp/McpSession.kt"),
    Path("scripts/smoke-packaged-k5-completion.py"),
    Path("scripts/finalize-native-k5-completion.py"),
    Path("scripts/test-finalize-native-k5-completion.py"),
}
REQUIRED_JARS = {
    f"refactorkit-{module}-0.7.0-SNAPSHOT.jar"
    for module in ("core", "java", "kotlin", "jvm", "daemon", "mcp", "cli")
}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def detected_platform() -> str:
    system = {"linux": "linux", "windows": "windows", "darwin": "macos"}.get(
        host_platform.system().lower()
    )
    machine = host_platform.machine().lower()
    architecture = (
        "x86_64" if machine in {"x86_64", "amd64"}
        else "aarch64" if machine in {"aarch64", "arm64"}
        else None
    )
    if system is None or architecture is None:
        raise ValueError(f"unsupported qualification host: {host_platform.system()}/{machine}")
    return f"{system}-{architecture}"


def exact_java_version(java: Path, label: str) -> str:
    result = subprocess.run([str(java), "-version"], text=True, capture_output=True, check=True)
    text = (result.stderr + result.stdout).strip()
    if (re.search(r'^openjdk version "21\.0\.11"', text, re.MULTILINE) is None or
            re.search(r'\bbuild 21\.0\.11\+10(?:-LTS)?\b', text) is None):
        raise ValueError(f"{label} is not exact qualified JDK 21.0.11+10: {text}")
    return text


def parse_count(root: ET.Element, field: str, report: Path) -> int:
    try:
        value = int(root.attrib.get(field, ""))
    except ValueError as problem:
        raise ValueError(f"invalid JUnit {field} in {report}") from problem
    if value < 0:
        raise ValueError(f"negative JUnit {field} in {report}")
    return value


def valid_checksum_record(fields: list[str], requirement: PurePath = REQUIREMENT) -> bool:
    return len(fields) == 2 and fields[0] == REQUIREMENT_SHA256 and fields[1] == requirement.as_posix()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--platform", required=True)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--smoke-log", type=Path, required=True)
    parser.add_argument("--report-root", type=Path, required=True)
    parser.add_argument("--package-root", type=Path, required=True)
    parser.add_argument("--repository-root", type=Path, default=Path("."))
    parser.add_argument("--output", type=Path, required=True)
    options = parser.parse_args()
    repository = options.repository_root.resolve()

    if options.platform not in PLATFORMS:
        raise ValueError(f"unsupported qualification platform: {options.platform}")
    actual_platform = detected_platform()
    if options.platform != actual_platform:
        raise ValueError(f"qualification platform mismatch: declared {options.platform}, host is {actual_platform}")
    if re.fullmatch(r"[0-9a-f]{40}", options.revision) is None:
        raise ValueError("revision must be an exact 40-character lowercase Git SHA")
    current_revision = subprocess.run(
        ["git", "-C", str(repository), "rev-parse", "HEAD"], check=True, text=True, capture_output=True,
    ).stdout.strip()
    if current_revision != options.revision:
        raise ValueError(f"revision mismatch: expected {options.revision}, checkout is {current_revision}")
    revision_line = subprocess.run(
        ["git", "-C", str(repository), "rev-list", "--parents", "-n", "1", current_revision],
        check=True, text=True, capture_output=True,
    ).stdout.strip().split()
    if len(revision_line) != 2:
        raise ValueError("qualification revision must be one exact non-merge source commit")
    status = subprocess.run(
        ["git", "-C", str(repository), "status", "--porcelain", "--untracked-files=all"],
        check=True, text=True, capture_output=True,
    ).stdout.strip()
    if status:
        raise ValueError("tracked or untracked checkout is dirty; receipt cannot bind uncommitted input")
    subprocess.run(["git", "-C", str(repository), "diff", "--check", "HEAD"], check=True, capture_output=True)

    requirement = repository / REQUIREMENT
    checksum = requirement.with_suffix(requirement.suffix + ".sha256")
    if not requirement.is_file() or sha256(requirement) != REQUIREMENT_SHA256:
        raise ValueError("sealed K5 completion requirement is missing or drifted")
    if not checksum.is_file() or not valid_checksum_record(checksum.read_text(encoding="utf-8").strip().split()):
        raise ValueError("sealed K5 completion checksum record is invalid")
    for relative, expected in BOUND_REQUIREMENTS.items():
        path = repository / relative
        if not path.is_file() or sha256(path) != expected:
            raise ValueError(f"bound K5 completion requirement is missing or drifted: {relative}")

    smoke_lines = [line for line in options.smoke_log.read_text(encoding="utf-8").splitlines() if line.strip()]
    if smoke_lines.count(SMOKE_MARKER) != 1 or smoke_lines[-1] != SMOKE_MARKER:
        raise ValueError("packaged K5 completion smoke lacks one terminal exact marker")
    if any("::error" in line or "Traceback" in line for line in smoke_lines):
        raise ValueError("packaged K5 completion smoke contains failure evidence")

    reports = sorted(options.report_root.glob("*/build/test-results/test/TEST-*.xml"))
    if len(reports) != len(REQUIRED_SUITES):
        raise ValueError(f"expected exactly {len(REQUIRED_SUITES)} focused reports, found {len(reports)}")
    suites: dict[str, dict[str, object]] = {}
    for report in reports:
        root = ET.parse(report).getroot()
        suite = root.attrib.get("name", "")
        required = REQUIRED_SUITES.get(suite)
        if required is None or suite in suites:
            raise ValueError(f"unexpected or duplicate focused suite: {suite}")
        counts = {field: parse_count(root, field, report) for field in ("tests", "skipped", "failures", "errors")}
        if counts != {"tests": len(required), "skipped": 0, "failures": 0, "errors": 0}:
            raise ValueError(f"focused K5 completion suite is incomplete: {suite}: {counts}")
        cases = [case.attrib.get("name", "") for case in root.findall("testcase")]
        if len(cases) != len(set(cases)) or set(cases) != required:
            raise ValueError(f"focused K5 completion cases differ from oracle: {suite}: {sorted(cases)}")
        suites[suite] = {
            "requiredTests": sorted(required), "counts": counts,
            "report": report.as_posix(), "reportSha256": sha256(report),
        }

    java_name = "java.exe" if os.name == "nt" else "java"
    java_home = os.environ.get("JAVA_HOME", "")
    if not java_home:
        raise ValueError("JAVA_HOME is required")
    host_java = Path(java_home).resolve() / "bin" / java_name
    if not host_java.is_file():
        raise ValueError("qualification host Java is missing")
    host_version = exact_java_version(host_java, "qualification host Java")
    package_root = options.package_root.resolve()
    embedded_java = package_root / "runtime/bin" / java_name
    launcher_suffix = ".bat" if os.name == "nt" else ""
    for launcher in ("refactorkit", "refactorkit-daemon", "refactorkit-mcp"):
        if not (package_root / "bin" / f"{launcher}{launcher_suffix}").is_file():
            raise ValueError(f"packaged launcher is missing: {launcher}")
    if not embedded_java.is_file():
        raise ValueError("embedded package Java is missing")
    embedded_version = exact_java_version(embedded_java, "embedded Java")
    jars = sorted((package_root / "lib").glob("refactorkit-*-0.7.0-SNAPSHOT.jar"))
    jar_map = {path.name: sha256(path) for path in jars if path.name in REQUIRED_JARS}
    if set(jar_map) != REQUIRED_JARS:
        raise ValueError(f"packaged subject JARs differ from exact set: {sorted(jar_map)}")

    missing = sorted(str(path) for path in SUBJECT_FILES if not (repository / path).is_file())
    if missing:
        raise ValueError(f"K5 completion subject files are missing: {missing}")
    subject_hashes = {path.as_posix(): sha256(repository / path) for path in sorted(SUBJECT_FILES)}
    output = options.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    receipt = {
        "schemaVersion": 1,
        "scope": "v0.7.0-k5-bounded-completion",
        "status": "PASSED",
        "revision": current_revision,
        "platform": options.platform,
        "detectedPlatform": actual_platform,
        "requirement": {
            "path": REQUIREMENT.as_posix(), "sha256": REQUIREMENT_SHA256,
            "checksumRecordSha256": sha256(checksum),
            "bound": {path.as_posix(): digest for path, digest in BOUND_REQUIREMENTS.items()},
        },
        "junit": {"totalRequiredTests": sum(map(len, REQUIRED_SUITES.values())), "suites": suites},
        "packagedSmokeSha256": sha256(options.smoke_log),
        "packagedSubjectJarSha256": dict(sorted(jar_map.items())),
        "subjectFileSha256": subject_hashes,
        "host": {
            "system": host_platform.system(), "machine": host_platform.machine(),
            "javaVersionOutput": host_version, "javaSha256": sha256(host_java),
        },
        "embeddedRuntime": {"versionOutput": embedded_version, "javaSha256": sha256(embedded_java)},
        "boundaries": [
            "Callable/type import removal is isolated-counterfactual K2 only; formatting is import-layout only.",
            "Change signature is parameter-name-only across exact source families with unchanged JVM descriptors.",
            "Extract/inline is one zero-input integer expression shape only.",
            "Advanced shapes are read-only facts; delegated/platform/plugin/generated/framework cases remain refused.",
            "No general Kotlin support, whole-repository CI, installed asset, SBOM, signing, or release claim.",
        ],
    }
    output.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Finalized K5 completion native receipt: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
