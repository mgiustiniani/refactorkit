#!/usr/bin/env python3
"""Fail closed when the v0.7.0 support projection outruns its open roadmap rows."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

OPEN_PLAN_ROWS = {
    "kotlin-depth": "- [ ] Move declaration/file/package with import and Java interoperability updates.",
    "typescript-advanced": "- [ ] Source-file relocation using exact `getEditsForFileRename` authority,",
    "maven-module-depth": "- [ ] Continue Java change-signature, move/package/module, extract/inline and",
    "maven-module-ownership": "- [ ] Add transactional Java ownership migration across Maven reactor modules",
    "maven-module-native": "- [ ] Qualify module rename/move next using multi-module Maven fixtures and",
    "release-sbom": "- [ ] Publish support matrix, limitations, migration notes, checksums, SPDX SBOMs,",
    "release-evidence": "- [ ] Produce one versioned release evidence manifest joining repository/tag/full",
}

CLOSED_PLAN_ROWS = {
    "kotlin-support": "- [x] Publish qualified Kotlin/JDK/Gradle/Maven support rows and license/SBOM",
    "kotlin-identity": "- [x] Model JVM binary names, Kotlin source declarations and callable/property",
    "shared-jvm-identity": "- [x] Establish cross-language Java/Kotlin declaration and reference identity for",
    "kotlin-capability-boundaries": "- [x] Keep Kotlin/JVM, Kotlin Multiplatform, Android, generated code, compiler",
    "kotlin-build-spi": "- [x] Validate the Build Model SPI against Kotlin so any remaining Java/Maven",
    "persistent-jvm-state": "- [x] Continue from the snapshot-keyed normalized JDT cache to finer persistent",
    "shared-refresh-orchestration": "- [x] Continue targeted extraction of shared workspace-refresh lifecycle, apply,",
}

REQUIRED_SUPPORT_CLAIMS = {
    "snapshot": "Status: active `0.7.0-SNAPSHOT` qualification ledger.",
    "projection-boundary": "does not close K5, T5, J1, I1, or the release as a whole.",
    "generic-native-boundary": "Historical generic native acceptance does not qualify an operation-specific row.",
    "kotlin-boundary": "General Kotlin managed support and every unchecked K5 operation remain unqualified.",
    "typescript-boundary": "All nine unchecked T5 advanced-operation rows remain unqualified.",
    "module-boundary": "Packaged/native/cross-platform module rename or move and general Maven-module authority remain unqualified.",
    "recipe-boundary": "Recipe evidence is operation-specific; no generic or advanced migration-recipe authority is claimed.",
    "sbom-boundary": "SBOM workflow wiring is configured, but final SPDX assets, attestations, publication, and downloaded-asset verification remain unqualified.",
    "move-class-native-boundary": "Native evidence state is explicit and uniform; no row was qualified before independent review.",
}

REQUIRED_NATIVE_WORKFLOW_TOKENS = {
    "dedicated-job": "maven-move-class-native:",
    "exact-head-checkout": "ref: ${{ github.event.pull_request.head.sha || github.sha }}",
    "public-cli-task": ":modules:refactorkit-cli:packagedMavenMoveClassAuthorityTest",
    "complete-matrix-task": ":modules:refactorkit-cli:packagedMavenMoveClassAuthorityMatrixTest",
    "revision-bound-finalizer": "scripts/finalize-native-maven-move-class-authority.py",
    "always-upload": "Upload Maven move-class native qualification (${{ matrix.platform }})",
}

K1_NATIVE_JOB_MARKER = "  k1-k2-shared-foundations-native:"
REQUIRED_K1_NATIVE_WORKFLOW_TOKENS = {
    "exact-source-head-checkout": "ref: ${{ github.event.pull_request.head.sha || github.sha }}",
    "exact-source-head-receipt": '"--revision", "${{ github.event.pull_request.head.sha || github.sha }}"',
    "linux-host": "platform: linux-x86_64",
    "windows-host": "platform: windows-x86_64",
    "macos-intel-host": "platform: macos-x86_64",
    "macos-arm-host": "platform: macos-aarch64",
    "revision-bound-finalizer": "scripts/finalize-native-k1-k2-shared-foundations.py",
    "always-upload": "if: always()",
}

PROHIBITED_SUPPORT_CLAIMS = (
    "REQ-013 is still `@absent`",
    "candidate REQ-013",
    "all four Maven move-class native rows are qualified",
    "broad Maven module rename support",
    "general Kotlin managed support is qualified",
    "T5 advanced operations are qualified",
)


def verify(repository_root: Path) -> dict[str, object]:
    plan_path = repository_root / "docs/releases/v0.7.0-plan.md"
    support_path = repository_root / "docs/releases/v0.7.0-support-matrix.md"
    workflow_path = repository_root / ".github/workflows/ci.yml"
    plan = plan_path.read_text(encoding="utf-8")
    support = support_path.read_text(encoding="utf-8")
    workflow = workflow_path.read_text(encoding="utf-8")
    failures: list[str] = []

    for name, row in OPEN_PLAN_ROWS.items():
        if row not in plan:
            failures.append(f"roadmap row is no longer explicitly open: {name}")
    for name, row in CLOSED_PLAN_ROWS.items():
        if row not in plan:
            failures.append(f"qualified roadmap row is not explicitly closed: {name}")

    for name, claim in REQUIRED_SUPPORT_CLAIMS.items():
        if claim not in support:
            failures.append(f"support boundary is missing: {name}")

    for claim in PROHIBITED_SUPPORT_CLAIMS:
        if claim in support:
            failures.append(f"support projection contains prohibited claim: {claim}")

    for name, token in REQUIRED_NATIVE_WORKFLOW_TOKENS.items():
        if token not in workflow:
            failures.append(f"dedicated native workflow token is missing: {name}")

    k1_native_workflow = ""
    if K1_NATIVE_JOB_MARKER not in workflow:
        failures.append("dedicated K1/K2 native workflow job is missing")
    else:
        k1_native_workflow = workflow.split(K1_NATIVE_JOB_MARKER, 1)[1]
        for name, token in REQUIRED_K1_NATIVE_WORKFLOW_TOKENS.items():
            if token not in k1_native_workflow:
                failures.append(f"dedicated K1/K2 native workflow token is missing: {name}")

    native_states = re.findall(
        r"^\| (?:Linux|Windows|macOS) \| [^|]+ \| public-CLI REQ-001 plus "
        r"packaged-production 13-ID/31-case matrix \| `([^`]+)` \|$",
        support,
        flags=re.MULTILINE,
    )
    if len(native_states) != 4 or len(set(native_states)) != 1:
        failures.append(
            "Maven move-class native ledger must contain four rows in one uniform state; "
            f"observed {native_states}"
        )
    native_state = native_states[0] if len(native_states) == 4 and len(set(native_states)) == 1 else "INVALID"
    allowed_states = {"CONFIGURED_UNOBSERVED", "PASS_REVIEW_PENDING", "PASSED"}
    if native_state not in allowed_states:
        failures.append(f"Maven move-class native ledger has unsupported state: {native_state}")
    native_parent_rows_open = (
        "- [ ] Prove semantic preview, one-transaction apply, exact post-apply diagnostics," in plan
        or "- [ ] Prove the remaining authority/refusal shapes through separately scoped" in plan
    )
    if native_parent_rows_open and native_state == "PASSED":
        failures.append("native rows cannot be PASSED while either P0 native parent row is open")
    if not native_parent_rows_open and native_state != "PASSED":
        failures.append("closed P0 native parent rows require all four native rows to be PASSED")

    exact_jdk_count = workflow.count("java-version: '21.0.11+10.0.LTS'")
    if exact_jdk_count != 4:
        failures.append(
            "CI must pin build, dedicated authority, runtime, and K1/K2 foundation jobs to exactly "
            f"four setup-java 21.0.11+10.0.LTS entries, found {exact_jdk_count}"
        )
    if "java-version: '21.0.11+9'" in workflow:
        failures.append("CI contains unavailable setup-java version 21.0.11+9")

    result: dict[str, object] = {
        "schemaVersion": 1,
        "status": "PASSED" if not failures else "FAILED",
        "openRoadmapRowsVerified": sorted(OPEN_PLAN_ROWS),
        "closedRoadmapRowsVerified": sorted(CLOSED_PLAN_ROWS),
        "supportBoundariesVerified": sorted(REQUIRED_SUPPORT_CLAIMS),
        "nativeWorkflowTokensVerified": sorted(REQUIRED_NATIVE_WORKFLOW_TOKENS),
        "k1NativeWorkflowTokensVerified": sorted(REQUIRED_K1_NATIVE_WORKFLOW_TOKENS),
        "nativeRows": len(native_states),
        "nativeEvidenceState": native_state,
        "nativeParentRowsOpen": native_parent_rows_open,
        "exactTemurin2111Pins": exact_jdk_count,
        "failures": failures,
    }
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--repository-root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
    )
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    result = verify(args.repository_root.resolve())
    rendered = json.dumps(result, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    sys.stdout.write(rendered)
    return 0 if result["status"] == "PASSED" else 1


if __name__ == "__main__":
    raise SystemExit(main())
