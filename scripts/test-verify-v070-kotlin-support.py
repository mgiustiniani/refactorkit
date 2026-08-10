#!/usr/bin/env python3
from __future__ import annotations

import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FILES = [
    "docs/releases/v0.7.0-kotlin-support.json",
    "docs/api-0.2-kotlin-symbols-schema.json",
    "modules/refactorkit-kotlin/build.gradle.kts",
    "modules/refactorkit-kotlin/src/main/kotlin/org/refactorkit/kotlin/KotlinToolchainDiscovery.kt",
    "modules/refactorkit-kotlin/src/main/kotlin/org/refactorkit/kotlin/KotlinLanguageAdapter.kt",
    "scripts/smoke-packaged-kotlin.py",
    "scripts/verify-v070-kotlin-support.py",
]


class KotlinSupportVerifierTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        for relative in FILES:
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / relative, target)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def run_verifier(self):
        result = subprocess.run(
            ["python3", str(self.root / "scripts/verify-v070-kotlin-support.py")],
            cwd=self.root,
            text=True,
            capture_output=True,
            check=False,
        )
        return result, json.loads(result.stdout)

    def projection_passes(self) -> None:
        result, receipt = self.run_verifier()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("PASSED", receipt["status"])
        self.assertEqual(7, receipt["capabilityRows"])
        self.assertEqual("configured-unpublished", receipt["sbomState"])

    def test_projection_passes(self) -> None:
        self.projection_passes()

    def test_general_kotlin_support_widening_fails(self) -> None:
        path = self.root / "docs/releases/v0.7.0-kotlin-support.json"
        payload = json.loads(path.read_text())
        payload["capabilityBoundaries"][0]["state"] = "supported"
        path.write_text(json.dumps(payload))
        result, receipt = self.run_verifier()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Kotlin capability/refusal matrix", " ".join(receipt["failures"]))

    def test_missing_non_claim_fails(self) -> None:
        path = self.root / "docs/releases/v0.7.0-kotlin-support.json"
        payload = json.loads(path.read_text())
        payload["nonClaims"].pop()
        path.write_text(json.dumps(payload))
        result, receipt = self.run_verifier()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("non-claims", " ".join(receipt["failures"]))

    def test_toolchain_source_version_drift_fails(self) -> None:
        path = self.root / "modules/refactorkit-kotlin/src/main/kotlin/org/refactorkit/kotlin/KotlinToolchainDiscovery.kt"
        path.write_text(path.read_text().replace('QUALIFIED_KOTLIN_VERSION = "2.0.21"', 'QUALIFIED_KOTLIN_VERSION = "2.1.0"'))
        result, receipt = self.run_verifier()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("toolchain policy token missing", " ".join(receipt["failures"]))

    def test_capability_refusal_source_drift_fails(self) -> None:
        path = self.root / "modules/refactorkit-kotlin/src/main/kotlin/org/refactorkit/kotlin/KotlinLanguageAdapter.kt"
        path.write_text(path.read_text().replace('operation = "android"', 'operation = "androidUnsupported"', 1))
        result, receipt = self.run_verifier()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("capability refusal token missing", " ".join(receipt["failures"]))

    def test_packaged_command_timeout_regression_fails(self) -> None:
        path = self.root / "scripts/smoke-packaged-kotlin.py"
        path.write_text(path.read_text().replace("COMMAND_TIMEOUT_SECONDS = 180", "COMMAND_TIMEOUT_SECONDS = 60"))
        result, receipt = self.run_verifier()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("qualification command timeout", " ".join(receipt["failures"]))

    def test_constructor_schema_drift_fails(self) -> None:
        path = self.root / "docs/api-0.2-kotlin-symbols-schema.json"
        payload = json.loads(path.read_text())
        payload["$defs"]["symbol"]["properties"]["kind"]["enum"].remove("constructor")
        path.write_text(json.dumps(payload))
        result, receipt = self.run_verifier()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("symbol schema omits constructor", " ".join(receipt["failures"]))

    def test_premature_final_sbom_claim_fails(self) -> None:
        path = self.root / "docs/releases/v0.7.0-kotlin-support.json"
        payload = json.loads(path.read_text())
        payload["sbomBoundary"]["state"] = "published"
        path.write_text(json.dumps(payload))
        result, receipt = self.run_verifier()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("configured-unpublished", " ".join(receipt["failures"]))


if __name__ == "__main__":
    unittest.main()
