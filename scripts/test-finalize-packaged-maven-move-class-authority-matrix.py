#!/usr/bin/env python3
"""Focused fail-closed tests for the packaged move-class matrix finalizer."""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

EXPECTED = {
    "req-001": 1,
    "req-002": 1,
    "req-003": 1,
    "req-004": 1,
    "req-005-006": 2,
    "req-007": 1,
    "req-008": 6,
    "req-009": 1,
    "req-010": 1,
    "req-011": 1,
    "req-012": 6,
    "req-013": 9,
}


class MatrixFinalizerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.repository = self.root / "repository"
        self.package = self.root / "package"
        self.build = self.root / "build"
        self.output = self.root / "manifest.json"
        (self.repository / "features").mkdir(parents=True)
        (self.repository / "docs/requirements").mkdir(parents=True)
        (self.repository / "features/java-maven-move-class-apply-authority.feature").write_text(
            "Feature: fixture\n", encoding="utf-8"
        )
        (self.repository / "docs/requirements/req-java-maven-move-auth-013-baseline.md").write_text(
            "baseline\n", encoding="utf-8"
        )
        (self.package / "lib").mkdir(parents=True)
        (self.package / "lib/refactorkit-core.jar").write_bytes(b"jar")
        (self.package / "runtime").mkdir(parents=True)
        (self.package / "runtime/release").write_text("JAVA_VERSION=21\n", encoding="utf-8")
        self._write_receipts()

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _write_receipts(self) -> None:
        cucumber = self.build / "reports/cucumber"
        cucumber.mkdir(parents=True)
        for slug, count in EXPECTED.items():
            scenarios = []
            for index in range(count):
                scenarios.append({
                    "type": "scenario",
                    "name": f"{slug}-{index}",
                    "before": [{"result": {"status": "passed"}}],
                    "steps": [{"result": {"status": "passed"}}],
                    "after": [{"result": {"status": "passed"}}],
                })
            (cucumber / f"packaged-maven-move-class-authority-{slug}.json").write_text(
                json.dumps([{"elements": scenarios}]), encoding="utf-8"
            )
            suffix = "".join(part.capitalize() for part in slug.split("-"))
            self._write_junit(
                self.build / "test-results" /
                f"packagedMavenMoveClassAuthority{suffix}MatrixTest",
                count,
            )
        self._write_junit(
            self.build / "test-results/packagedMavenMoveClassAuthorityClasspathAttestationTest",
            1,
        )

    @staticmethod
    def _write_junit(directory: Path, tests: int) -> None:
        directory.mkdir(parents=True)
        cases = "".join(f'<testcase name="case-{index}"/>' for index in range(tests))
        (directory / "TEST-fixture.xml").write_text(
            f'<testsuite tests="{tests}" skipped="0" failures="0" errors="0">{cases}</testsuite>',
            encoding="utf-8",
        )

    def _run(self) -> subprocess.CompletedProcess[str]:
        script = Path(__file__).with_name("finalize-packaged-maven-move-class-authority-matrix.py")
        return subprocess.run(
            [
                sys.executable,
                str(script),
                "--repository-root", str(self.repository),
                "--package-root", str(self.package),
                "--build-root", str(self.build),
                "--output", str(self.output),
            ],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )

    def test_complete_matrix_passes(self) -> None:
        result = self._run()
        manifest = json.loads(self.output.read_text(encoding="utf-8"))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("PASSED", manifest["status"])
        self.assertEqual(31, manifest["totals"]["scenarios"])

    def test_missing_receipt_writes_failed_manifest_and_exits_nonzero(self) -> None:
        (self.build / "reports/cucumber/packaged-maven-move-class-authority-req-008.json").unlink()
        result = self._run()
        manifest = json.loads(self.output.read_text(encoding="utf-8"))
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("FAILED", manifest["status"])
        self.assertIn("FileNotFoundError", manifest["failure"])

    def test_nonpassing_step_writes_failed_manifest_and_exits_nonzero(self) -> None:
        path = self.build / "reports/cucumber/packaged-maven-move-class-authority-req-013.json"
        document = json.loads(path.read_text(encoding="utf-8"))
        document[0]["elements"][0]["steps"][0]["result"]["status"] = "failed"
        path.write_text(json.dumps(document), encoding="utf-8")
        result = self._run()
        manifest = json.loads(self.output.read_text(encoding="utf-8"))
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("FAILED", manifest["status"])
        self.assertIn("non-passing", manifest["failure"])


if __name__ == "__main__":
    unittest.main()
