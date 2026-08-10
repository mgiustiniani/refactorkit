#!/usr/bin/env python3
"""Finalize one fail-closed native K5 move-next qualification receipt."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePath
import platform as host_platform
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


PLATFORMS = {"linux-x86_64", "windows-x86_64", "macos-x86_64", "macos-aarch64"}
SUITE = "org.refactorkit.jvm.KotlinJavaPublicTypeRenamePlannerTest"
REQUIRED_TESTS = {
    "publicTopLevelKotlinFunctionMoveUpdatesExactImportAndRollsBack()",
    "publicTopLevelKotlinFunctionMoveSupportsImplicitPublicLowercaseFileFacade()",
    "publicTopLevelKotlinFunctionMoveSupportsRetainedCommentBetweenPublicAndFun()",
    "publicTopLevelKotlinFunctionMoveRefusesCrossFileOverloadFamily()",
    "publicTopLevelKotlinFunctionMoveRefusesDestinationPackageOverloadFamily()",
    "publicTopLevelKotlinFunctionMoveRefusesExternalDestinationPackageOverloadFamily()",
    "publicTopLevelKotlinFunctionMoveRefusesExternalDestinationFileFacadeCollision()",
    "publicTopLevelKotlinFunctionMovePreservesExplicitNonMovedDescriptorType()",
    "publicTopLevelKotlinFunctionMoveRefusesOutboundBindingSubstitution()",
    "publicTopLevelKotlinFunctionMoveRefusesOutboundCallableReference()",
    "publicTopLevelKotlinFunctionMoveRefusesCallableReferenceInPrivateHelper()",
    "publicTopLevelKotlinFunctionMoveRefusesImplicitIteratorBindingSubstitution()",
    "publicTopLevelKotlinFunctionMoveRefusesExternalPackageFunctionBindingSubstitution()",
    "publicTopLevelKotlinFunctionMoveRequiresExternalConsumerApproval()",
    "publicTopLevelKotlinFunctionMoveRefusesPublicMemberInsidePrivateContainer()",
    "publicTopLevelKotlinFunctionMoveRefusesExactJavaConsumer()",
    "publicTopLevelKotlinFunctionMoveRefusesDefaultParameterShape()",
    "publicTopLevelKotlinFunctionMoveRefusesExcludedDeclarationShapes()",
    "publicTopLevelKotlinFunctionMoveRefusesExcludedConsumerShapes()",
    "standaloneCompanionMoveHasStableRefusalAndNoEdits()",
}
COMPANION_EVIDENCE_SUITE = "org.refactorkit.kotlin.KotlinCompilerDiagnosticsTest"
COMPANION_EVIDENCE_TEST = "successfulK2CompilationReturnsDurableJvmTypeSymbolsWithExactPsiRanges()"
DIAGNOSTICS_ROUTE_SUITE = "org.refactorkit.jvm.KotlinMoveDeclarationDiagnosticsRouteTest"
DIAGNOSTICS_ROUTE_TESTS = {
    "kotlinOnlyMoveUsesLazyOperationDiagnosticsRatherThanGenericK2()",
    "kotlinOnlyNonMoveRetainsLazyGenericK2Fallback()",
}
REQUIRED_SUITES = {
    SUITE: REQUIRED_TESTS,
    COMPANION_EVIDENCE_SUITE: {COMPANION_EVIDENCE_TEST},
    DIAGNOSTICS_ROUTE_SUITE: DIAGNOSTICS_ROUTE_TESTS,
}
REQUIREMENT = Path("docs/requirements/kotlin-jvm-move-top-level-function-and-companion-refusal.md")
REQUIREMENT_SHA256 = "52dd7de86022e2d86e143c453fe2c445c2718149a6393d5ba462bbceb2642a0f"
DIAGNOSTICS_FEATURE = Path("features/managed-apply-diagnostics-gate-selector.feature")
DIAGNOSTICS_FEATURE_SHA256 = "63f0a250df2fd2dea5fd5fc1d13471af89158ef2566e73439c3241df27b6b8c1"
SMOKE_MARKER = (
    "Packaged K5 move-next acceptance passed: exact-import top-level function move "
    "preview/apply/rollback and standalone companion refusal."
)
SUBJECT_FILES = {
    Path(".github/workflows/ci.yml"),
    REQUIREMENT,
    Path("docs/kotlin-adapter.md"),
    Path("docs/releases/v0.7.0-k5-move-next-acceptance.md"),
    Path("docs/requirements/managed-apply-diagnostics-gate-selector-k5-change.md"),
    Path("docs/requirements/managed-apply-diagnostics-gate-selector-k5-change-signature-change.md"),
    Path("docs/requirements/kotlin-jvm-move-top-level-function-and-companion-refusal-approved-change-001.md"),
    Path("docs/requirements/kotlin-jvm-move-top-level-function-and-companion-refusal-approved-change-002.md"),
    Path("docs/requirements/kotlin-jvm-move-top-level-function-and-companion-refusal-approved-change-003.md"),
    Path("docs/releases/v0.7.0-support-matrix.md"),
    Path("docs/requirements/evidence/v0.7.0-k5-move-next-red-bbaf93d-a92aa9b803f0a3bd62f545d305f8c748bac16ecbf81c3eb629c31aaaae43a67b.log"),
    Path("docs/requirements/evidence/v0.7.0-k5-companion-evidence-red-bbaf93d-a6dc4c2ecbffb329be4899d4cb172b9045555866575f3dd23b9228273e3bda59.log"),
    Path("docs/requirements/evidence/v0.7.0-k5-pre-native-review-boundary-red-72ae4fd09c6ea96531db396c6cf10344981a7c4efc0e060c911cec6424a16c87.log"),
    Path("docs/requirements/evidence/v0.7.0-k5-pre-native-review-fail-f715fc8beeeb0d67fa9888ec7ad1261364969df4899675a6a7154df28de8862b.txt"),
    Path("docs/requirements/evidence/v0.7.0-k5-pre-native-review-v2-fail-131e3481b562a5093a1a439756806121a2d357e96d03ca19dbd6e8662837b0b8.txt"),
    Path("docs/requirements/evidence/v0.7.0-k5-pre-native-review-member-red-288e8c43c856a72d838fbfe405342746e86c74df02ed3df42653f28b4118bfe5.log"),
    Path("docs/requirements/evidence/v0.7.0-k5-pre-native-review-v3-fail-3f6c4644e22a60fde0dd7bd1d4c39866fda94b6ac8c7159d9dfbf3f58e3f0ebb.txt"),
    Path("docs/requirements/evidence/v0.7.0-k5-pre-native-review-v3-boundary-red-bc9ce3d0588837727595a1a4fb09e1878b356ffa60a009da0d57acb9972a8747.log"),
    Path("docs/requirements/evidence/v0.7.0-k5-pre-native-review-v4-fail-e38cd0bbf8f5da8109ed1f4e6d0710059d7c15bfd2468b6b624719ff8279cacf.txt"),
    Path("docs/requirements/evidence/v0.7.0-k5-pre-native-review-v4-boundary-red-31da1bb16dee24b08a7191bc21b0d7627a7f4fd804950e7e8703264e1c7118d3.log"),
    Path("docs/requirements/evidence/v0.7.0-k5-pre-native-review-v5-fail-32292b23d8e797485e74daeedaec475b9b6a0cbeb25cb507c8b7f1fb2533eb29.txt"),
    Path("docs/requirements/evidence/v0.7.0-k5-native-shallow-checkout-red-run-31394432715-692d355d9d9a2bc586e7c264c0db93f697ecc08f2cbf278658b1114e9c0b64f2.json"),
    Path("docs/requirements/evidence/v0.7.0-k5-native-windows-checksum-red-run-31396325413-b7e0d2f0d2d469a7e575c4d109f6751b8a5c2918f03a5e68e430fca8a832a44b.json"),
    Path("docs/requirements/evidence/v0.7.0-k5-post-native-review-run-31398378153-fail-213a300e9ee6142cf455fd028471d874e229d89a7e851fd9691d2d7dd26b73e9.md"),
    Path("docs/requirements/evidence/v0.7.0-k5-post-native-review-run-31402329615-fail-3b0f8a576f898cd98c1ae19d8e2230b693a1168b8072dc63ce97b25c66ca2617.md"),
    Path("docs/requirements/evidence/v0.7.0-k5-post-native-review-run-31410944555-fail-c0819dac0b763ee9674ce2239ece662abdcf34581ab796fd21c1a138d3483bb4.md"),
    Path("docs/requirements/evidence/v0.7.0-k5-post-native-callable-reference-red-872f1f4-fe3c343d3e5535a7055be980cad5d1544e92b78e08df61521720efcd348eebfd.log"),
    Path("docs/requirements/evidence/kotlin-jvm-move-implicit-iterator-tests-only-red-3afd3f824129abc0491b1643a2993c2630ba3fe63df8f83a65d90d916a6ee4f5.log"),
    Path("docs/requirements/evidence/kotlin-jvm-move-implicit-conventions-green-e6c221977cd58070761e8a704d732804ea1a03859fb36aaea3a6eadead12eb39.log"),
    Path("docs/requirements/evidence/v0.7.0-k5-pre-native-review-v5-external-callable-red-24580296965d3ae9dd8ba417575408c3060be94500a77cabc61d6e76a345bfef.xml"),
    Path("docs/requirements/evidence/v0.7.0-k5-pre-native-review-v5-descriptor-projection-red-eb596021ce8f796a47dc60b774cd5cbae973a3eab9280ee43046584cab265322.xml"),
    Path("modules/refactorkit-kotlin/src/main/java/org/refactorkit/kotlin/bridge/KotlinCompilerBridgeMain.java"),
    Path("modules/refactorkit-kotlin/src/main/java/org/refactorkit/kotlin/bridge/KotlinCompilerSymbolExtractor.java"),
    Path("modules/refactorkit-kotlin/src/main/java/org/refactorkit/kotlin/bridge/KotlinCompilerUsageExtractor.java"),
    Path("modules/refactorkit-kotlin/src/main/kotlin/org/refactorkit/kotlin/KotlinCompilerDiagnostics.kt"),
    Path("modules/refactorkit-kotlin/src/test/kotlin/org/refactorkit/kotlin/KotlinCompilerDiagnosticsTest.kt"),
    DIAGNOSTICS_FEATURE,
    Path("modules/refactorkit-daemon/src/main/kotlin/org/refactorkit/daemon/DaemonSession.kt"),
    Path("modules/refactorkit-jvm/src/main/kotlin/org/refactorkit/jvm/KotlinJvmMoveDeclarationPlanner.kt"),
    Path("modules/refactorkit-jvm/src/main/kotlin/org/refactorkit/jvm/ManagedApplyDiagnosticsGateSelector.kt"),
    Path("modules/refactorkit-jvm/src/test/kotlin/org/refactorkit/jvm/KotlinJavaPublicTypeRenamePlannerTest.kt"),
    Path("modules/refactorkit-jvm/src/test/kotlin/org/refactorkit/jvm/KotlinMoveDeclarationDiagnosticsRouteTest.kt"),
    Path("modules/refactorkit-jvm/src/test/kotlin/org/refactorkit/jvm/manageddiagnostics/ManagedApplyDiagnosticsGateSelectorSteps.kt"),
    Path("scripts/smoke-packaged-k5-move-next.py"),
    Path("scripts/finalize-native-k5-move-next.py"),
    Path("scripts/test-finalize-native-k5-move-next.py"),
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
    return (
        len(fields) == 2
        and fields[0] == REQUIREMENT_SHA256
        and fields[1] == requirement.as_posix()
    )


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
        raise ValueError(
            f"qualification platform mismatch: declared {options.platform}, host is {actual_platform}"
        )
    if re.fullmatch(r"[0-9a-f]{40}", options.revision) is None:
        raise ValueError("revision must be an exact 40-character lowercase Git SHA")
    current_revision = subprocess.run(
        ["git", "-C", str(repository), "rev-parse", "HEAD"],
        check=True, text=True, capture_output=True,
    ).stdout.strip()
    if current_revision != options.revision:
        raise ValueError(f"revision mismatch: expected {options.revision}, checkout is {current_revision}")
    revision_line = subprocess.run(
        ["git", "-C", str(repository), "rev-list", "--parents", "-n", "1", current_revision],
        check=True, text=True, capture_output=True,
    ).stdout.strip().split()
    if len(revision_line) != 2:
        raise ValueError("qualification revision must be one exact non-merge source commit, not a synthetic merge revision")
    checkout_status = subprocess.run(
        ["git", "-C", str(repository), "status", "--porcelain", "--untracked-files=all"],
        check=True, text=True, capture_output=True,
    ).stdout.strip()
    if checkout_status:
        raise ValueError("tracked or untracked checkout is dirty; native receipt cannot bind an uncommitted subject")
    subprocess.run(
        ["git", "-C", str(repository), "diff", "--check", "HEAD"],
        check=True, text=True, capture_output=True,
    )

    requirement = repository / REQUIREMENT
    checksum_file = requirement.with_suffix(requirement.suffix + ".sha256")
    if not requirement.is_file() or not checksum_file.is_file():
        raise ValueError("sealed K5 move-next requirement or checksum is missing")
    if sha256(requirement) != REQUIREMENT_SHA256:
        raise ValueError("K5 move-next requirement differs from its reviewed immutable baseline")
    checksum_fields = checksum_file.read_text(encoding="utf-8").strip().split()
    if not valid_checksum_record(checksum_fields):
        raise ValueError("K5 move-next requirement checksum record is invalid")
    diagnostics_feature = repository / DIAGNOSTICS_FEATURE
    if not diagnostics_feature.is_file() or sha256(diagnostics_feature) != DIAGNOSTICS_FEATURE_SHA256:
        raise ValueError("managed diagnostics route feature differs from the K5 compatibility baseline")

    smoke_lines = [line for line in options.smoke_log.read_text(encoding="utf-8").splitlines() if line.strip()]
    if smoke_lines.count(SMOKE_MARKER) != 1 or smoke_lines[-1] != SMOKE_MARKER:
        raise ValueError("packaged K5 smoke log lacks one terminal exact passing marker")
    if any("::error" in line or "Traceback" in line for line in smoke_lines):
        raise ValueError("packaged K5 smoke log contains failure evidence")

    reports = sorted(options.report_root.glob("*/build/test-results/test/TEST-*.xml"))
    if len(reports) != len(REQUIRED_SUITES):
        raise ValueError(
            f"expected exactly {len(REQUIRED_SUITES)} focused JUnit reports, found {len(reports)}"
        )
    junit_suites: dict[str, dict[str, object]] = {}
    for report in reports:
        root = ET.parse(report).getroot()
        suite = root.attrib.get("name", "")
        required_cases = REQUIRED_SUITES.get(suite)
        if required_cases is None or suite in junit_suites:
            raise ValueError(f"unexpected or duplicate focused JUnit suite: {suite}")
        counts = {
            field: parse_count(root, field, report)
            for field in ("tests", "skipped", "failures", "errors")
        }
        expected_counts = {
            "tests": len(required_cases), "skipped": 0, "failures": 0, "errors": 0,
        }
        if counts != expected_counts:
            raise ValueError(f"focused K5 JUnit suite is incomplete or non-passing: {suite}: {counts}")
        cases = [case.attrib.get("name", "") for case in root.findall("testcase")]
        if len(cases) != len(set(cases)) or set(cases) != required_cases:
            raise ValueError(f"focused K5 JUnit cases differ from the exact oracle: {suite}: {sorted(cases)}")
        junit_suites[suite] = {
            "requiredTests": sorted(required_cases),
            "counts": counts,
            "report": report.as_posix(),
            "reportSha256": sha256(report),
        }
    if set(junit_suites) != set(REQUIRED_SUITES):
        raise ValueError(f"required focused K5 JUnit suites are missing: {sorted(set(REQUIRED_SUITES) - set(junit_suites))}")

    java_home_text = os.environ.get("JAVA_HOME", "")
    if not java_home_text:
        raise ValueError("JAVA_HOME is required for native qualification")
    host_java = Path(java_home_text).resolve() / "bin" / ("java.exe" if os.name == "nt" else "java")
    if not host_java.is_file():
        raise ValueError("qualification host Java is missing below JAVA_HOME")
    host_java_version = exact_java_version(host_java, "qualification host Java")

    package_root = options.package_root.resolve()
    embedded_java = package_root / "runtime/bin" / ("java.exe" if os.name == "nt" else "java")
    launcher = package_root / "bin" / ("refactorkit.bat" if os.name == "nt" else "refactorkit")
    daemon = package_root / "bin" / ("refactorkit-daemon.bat" if os.name == "nt" else "refactorkit-daemon")
    mcp = package_root / "bin" / ("refactorkit-mcp.bat" if os.name == "nt" else "refactorkit-mcp")
    if not all(path.is_file() for path in (embedded_java, launcher, daemon, mcp)):
        raise ValueError("packaged runtime Java or required K5 launchers are missing")
    embedded_java_version = exact_java_version(embedded_java, "embedded runtime")
    jar_paths = {
        path.name: path for path in (package_root / "lib").glob("refactorkit-*-0.7.0-SNAPSHOT.jar")
    }
    missing_jars = sorted(REQUIRED_JARS - jar_paths.keys())
    if missing_jars:
        raise ValueError(f"required packaged K5 subject jars are missing: {missing_jars}")

    subject_hashes: dict[str, str] = {}
    for relative in sorted(SUBJECT_FILES, key=str):
        subject = repository / relative
        if not subject.is_file():
            raise ValueError(f"native K5 subject file is missing: {relative}")
        subject_hashes[relative.as_posix()] = sha256(subject)

    receipt = {
        "schemaVersion": 1,
        "status": "PASSED",
        "scope": "v0.7.0-k5-move-next",
        "revision": options.revision,
        "platform": options.platform,
        "detectedPlatform": actual_platform,
        "host": {
            "system": host_platform.system(),
            "machine": host_platform.machine(),
            "javaVersionOutput": host_java_version,
            "javaSha256": sha256(host_java),
        },
        "requirement": {
            "path": REQUIREMENT.as_posix(),
            "sha256": REQUIREMENT_SHA256,
            "checksumRecordSha256": sha256(checksum_file),
            "diagnosticsRouteFeature": DIAGNOSTICS_FEATURE.as_posix(),
            "diagnosticsRouteFeatureSha256": DIAGNOSTICS_FEATURE_SHA256,
        },
        "packagedSmokeSha256": sha256(options.smoke_log),
        "junit": {
            "suites": {name: junit_suites[name] for name in sorted(junit_suites)},
            "totalRequiredTests": sum(len(cases) for cases in REQUIRED_SUITES.values()),
        },
        "embeddedRuntime": {
            "versionOutput": embedded_java_version,
            "javaSha256": sha256(embedded_java),
        },
        "subjectFileSha256": subject_hashes,
        "packagedSubjectJarSha256": {
            name: sha256(jar_paths[name]) for name in sorted(REQUIRED_JARS)
        },
        "boundaries": [
            "One plain public top-level function whole-file move with unaliased exact Kotlin imports only.",
            "Explicit and compiler-desugared outbound callable identities must remain exact across the staged move.",
            "Java, alias, star, same-package, qualified, callable-reference, overload, extension, suspend, default, JVM-name, multifile, generated, and plugin-dependent function forms remain refused.",
            "Standalone companion selection is refused; enclosing qualified whole-file type movement is unchanged.",
            "No general Kotlin managed-support, installed-runtime, final-SBOM, or whole-repository CI claim.",
        ],
    }
    options.output.parent.mkdir(parents=True, exist_ok=True)
    options.output.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(receipt, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as problem:
        print(f"qualification finalization failed: {problem}", file=sys.stderr)
        raise SystemExit(1)
