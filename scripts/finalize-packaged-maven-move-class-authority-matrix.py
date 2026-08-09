#!/usr/bin/env python3
"""Fail-closed manifest finalizer for the packaged Maven move-class matrix."""

from __future__ import annotations

import argparse
import hashlib
import json
import platform
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

EXPECTED = {
    "REQ-JAVA-MAVEN-MOVE-AUTH-001": ("req-001", 1),
    "REQ-JAVA-MAVEN-MOVE-AUTH-002": ("req-002", 1),
    "REQ-JAVA-MAVEN-MOVE-AUTH-003": ("req-003", 1),
    "REQ-JAVA-MAVEN-MOVE-AUTH-004": ("req-004", 1),
    "REQ-JAVA-MAVEN-MOVE-AUTH-005..006": ("req-005-006", 2),
    "REQ-JAVA-MAVEN-MOVE-AUTH-007": ("req-007", 1),
    "REQ-JAVA-MAVEN-MOVE-AUTH-008": ("req-008", 6),
    "REQ-JAVA-MAVEN-MOVE-AUTH-009": ("req-009", 1),
    "REQ-JAVA-MAVEN-MOVE-AUTH-010": ("req-010", 1),
    "REQ-JAVA-MAVEN-MOVE-AUTH-011": ("req-011", 1),
    "REQ-JAVA-MAVEN-MOVE-AUTH-012": ("req-012", 6),
    "REQ-JAVA-MAVEN-MOVE-AUTH-013": ("req-013", 9),
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def cucumber_receipt(path: Path, expected_scenarios: int) -> dict[str, object]:
    document = json.loads(path.read_text(encoding="utf-8"))
    scenarios = 0
    steps = 0
    hooks = 0
    statuses: list[str] = []
    for feature in document:
        for element in feature.get("elements", []):
            if element.get("type") != "scenario":
                continue
            scenarios += 1
            for hook_name in ("before", "after"):
                for hook in element.get(hook_name, []):
                    hooks += 1
                    statuses.append(hook.get("result", {}).get("status", "MISSING"))
            for step in element.get("steps", []):
                steps += 1
                statuses.append(step.get("result", {}).get("status", "MISSING"))
    if scenarios != expected_scenarios:
        raise ValueError(f"{path}: expected {expected_scenarios} scenarios, observed {scenarios}")
    if not statuses or any(status != "passed" for status in statuses):
        raise ValueError(f"{path}: non-passing or missing Cucumber result: {statuses}")
    return {
        "path": path.name,
        "sha256": sha256(path),
        "scenarios": scenarios,
        "steps": steps,
        "hooks": hooks,
        "status": "PASSED",
    }


def junit_receipt(directory: Path, expected_executed: int) -> dict[str, object]:
    files = sorted(directory.glob("TEST-*.xml"))
    if not files:
        raise ValueError(f"{directory}: no JUnit XML files")
    tests = skipped = failures = errors = 0
    for path in files:
        root = ET.parse(path).getroot()
        tests += int(root.attrib.get("tests", "0"))
        skipped += int(root.attrib.get("skipped", "0"))
        failures += int(root.attrib.get("failures", "0"))
        errors += int(root.attrib.get("errors", "0"))
    executed = tests - skipped
    if executed != expected_executed or failures or errors:
        raise ValueError(
            f"{directory}: expected executed={expected_executed}, observed "
            f"tests={tests} executed={executed} skipped={skipped} failures={failures} errors={errors}"
        )
    manifest_digest = hashlib.sha256()
    for path in files:
        manifest_digest.update(path.name.encode("utf-8"))
        manifest_digest.update(b"\0")
        manifest_digest.update(sha256(path).encode("ascii"))
        manifest_digest.update(b"\0")
    return {
        "xmlFiles": len(files),
        "tests": tests,
        "executed": executed,
        "skipped": skipped,
        "failures": failures,
        "errors": errors,
        "xmlManifestSha256": manifest_digest.hexdigest(),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository-root", type=Path, required=True)
    parser.add_argument("--package-root", type=Path, required=True)
    parser.add_argument("--build-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    repository = args.repository_root.resolve()
    package_root = args.package_root.resolve()
    build_root = args.build_root.resolve()
    output = args.output.resolve()
    manifest: dict[str, object] = {
        "schema": "refactorkit.packaged-maven-move-class-authority-matrix/v1",
        "status": "FAILED",
        "host": {"system": platform.system(), "machine": platform.machine()},
    }
    try:
        requirements = []
        total_scenarios = total_steps = total_hooks = 0
        for requirement, (slug, expected_scenarios) in EXPECTED.items():
            cucumber = cucumber_receipt(
                build_root / "reports" / "cucumber" /
                f"packaged-maven-move-class-authority-{slug}.json",
                expected_scenarios,
            )
            task_suffix = "".join(part.capitalize() for part in slug.split("-"))
            junit = junit_receipt(
                build_root / "test-results" /
                f"packagedMavenMoveClassAuthority{task_suffix}MatrixTest",
                expected_scenarios,
            )
            requirements.append({
                "requirement": requirement,
                "expectedScenarios": expected_scenarios,
                "cucumber": cucumber,
                "junit": junit,
            })
            total_scenarios += int(cucumber["scenarios"])
            total_steps += int(cucumber["steps"])
            total_hooks += int(cucumber["hooks"])

        attestation = junit_receipt(
            build_root / "test-results" / "packagedMavenMoveClassAuthorityClasspathAttestationTest",
            1,
        )
        production_jars = []
        for path in sorted((package_root / "lib").glob("refactorkit-*.jar")):
            production_jars.append({"name": path.name, "sha256": sha256(path)})
        if not production_jars:
            raise ValueError("packaged production JAR inventory is empty")
        runtime_release = package_root / "runtime" / "release"
        if not runtime_release.is_file():
            raise ValueError("packaged runtime/release is missing")

        manifest.update({
            "status": "PASSED",
            "featureSha256": sha256(repository / "features/java-maven-move-class-apply-authority.feature"),
            "req013BaselineSha256": sha256(
                repository / "docs/requirements/req-java-maven-move-auth-013-baseline.md"
            ),
            "runtimeReleaseSha256": sha256(runtime_release),
            "productionJars": production_jars,
            "classpathAttestation": attestation,
            "requirements": requirements,
            "totals": {
                "requirements": 13,
                "scenarios": total_scenarios,
                "steps": total_steps,
                "hooks": total_hooks,
            },
        })
        if total_scenarios != 31:
            raise ValueError(f"expected 31 total scenarios, observed {total_scenarios}")
    except Exception as error:  # Always leave a reviewable failure manifest.
        manifest["failure"] = f"{type(error).__name__}: {error}"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
    if manifest["status"] != "PASSED":
        print(manifest["failure"], file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
