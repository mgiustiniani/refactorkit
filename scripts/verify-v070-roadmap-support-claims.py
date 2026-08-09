#!/usr/bin/env python3
"""Fail closed when the v0.7.0 support projection outruns its open roadmap rows."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

OPEN_PLAN_ROWS = {
    "kotlin-support": "- [ ] Publish qualified Kotlin/JDK/Gradle/Maven support rows and license/SBOM",
    "kotlin-identity": "- [ ] Model JVM binary names, Kotlin source declarations and callable/property",
    "kotlin-depth": "- [ ] Move declaration/file/package with import and Java interoperability updates.",
    "typescript-advanced": "- [ ] Source-file relocation using exact `getEditsForFileRename` authority,",
    "maven-module-depth": "- [ ] Continue Java change-signature, move/package/module, extract/inline and",
    "maven-module-ownership": "- [ ] Add transactional Java ownership migration across Maven reactor modules",
    "maven-module-native": "- [ ] Qualify module rename/move next using multi-module Maven fixtures and",
    "release-sbom": "- [ ] Publish support matrix, limitations, migration notes, checksums, SPDX SBOMs,",
    "release-evidence": "- [ ] Produce one versioned release evidence manifest joining repository/tag/full",
}

REQUIRED_SUPPORT_CLAIMS = {
    "snapshot": "Status: active `0.7.0-SNAPSHOT` qualification ledger.",
    "projection-boundary": "does not close K5, T5, J1, I1, or the release as a whole.",
    "generic-native-boundary": "Historical generic native acceptance does not qualify an operation-specific row.",
    "kotlin-boundary": "General Kotlin managed support, the remaining K2 identity model, and every unchecked K5 operation remain unqualified.",
    "typescript-boundary": "All nine unchecked T5 advanced-operation rows remain unqualified.",
    "module-boundary": "Packaged/native/cross-platform module rename or move and general Maven-module authority remain unqualified.",
    "recipe-boundary": "Recipe evidence is operation-specific; no generic or advanced migration-recipe authority is claimed.",
    "sbom-boundary": "SBOM workflow wiring is configured, but final SPDX assets, attestations, publication, and downloaded-asset verification remain unqualified.",
    "move-class-native-boundary": "All four Maven move-class native rows remain `CONFIGURED_UNOBSERVED`.",
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
    plan = plan_path.read_text(encoding="utf-8")
    support = support_path.read_text(encoding="utf-8")
    failures: list[str] = []

    for name, row in OPEN_PLAN_ROWS.items():
        if row not in plan:
            failures.append(f"roadmap row is no longer explicitly open: {name}")

    for name, claim in REQUIRED_SUPPORT_CLAIMS.items():
        if claim not in support:
            failures.append(f"support boundary is missing: {name}")

    for claim in PROHIBITED_SUPPORT_CLAIMS:
        if claim in support:
            failures.append(f"support projection contains prohibited claim: {claim}")

    native_state_count = support.count("| `CONFIGURED_UNOBSERVED` |")
    if native_state_count != 4:
        failures.append(
            "Maven move-class native ledger must contain exactly four "
            f"CONFIGURED_UNOBSERVED rows, found {native_state_count}"
        )

    result: dict[str, object] = {
        "schemaVersion": 1,
        "status": "PASSED" if not failures else "FAILED",
        "openRoadmapRowsVerified": sorted(OPEN_PLAN_ROWS),
        "supportBoundariesVerified": sorted(REQUIRED_SUPPORT_CLAIMS),
        "configuredUnobservedNativeRows": native_state_count,
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
