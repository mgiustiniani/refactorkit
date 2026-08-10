#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
FINALIZER = ROOT / "scripts/finalize-native-k1-k2-shared-foundations.py"
SPEC = importlib.util.spec_from_file_location("k1k2_finalizer", FINALIZER)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class NativeK1K2SharedFinalizerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.repository = self.root / "repository"
        projection = self.repository / "docs/releases/v0.7.0-kotlin-support.json"
        projection.parent.mkdir(parents=True)
        shutil.copy2(ROOT / "docs/releases/v0.7.0-kotlin-support.json", projection)
        (self.repository / "subject.txt").write_text("sealed subject\n", encoding="utf-8")
        subprocess.run(["git", "init", "-q"], cwd=self.repository, check=True)
        subprocess.run(["git", "config", "user.name", "RefactorKit Test"], cwd=self.repository, check=True)
        subprocess.run(["git", "config", "user.email", "test@refactorkit.invalid"], cwd=self.repository, check=True)
        subprocess.run(["git", "add", "."], cwd=self.repository, check=True)
        subprocess.run(["git", "commit", "-qm", "sealed test subject"], cwd=self.repository, check=True)
        self.reports = self.root / "modules"
        report_dir = self.reports / "focused/build/test-results/test"
        report_dir.mkdir(parents=True)
        for index, suite in enumerate(sorted(MODULE.REQUIRED_SUITES)):
            (report_dir / f"TEST-{index}.xml").write_text(
                f'<testsuite name="{suite}" tests="1" skipped="0" failures="0" errors="0">'
                f'<testcase name="case" classname="{suite}"/></testsuite>\n',
                encoding="utf-8",
            )
        self.package = self.root / "package"
        (self.package / "runtime/bin").mkdir(parents=True)
        java = self.package / "runtime/bin/java"
        java.write_text(
            '#!/bin/sh\necho \'openjdk version "21.0.11"\' >&2\n'
            'echo \'OpenJDK Runtime Environment (build 21.0.11+10)\' >&2\n',
            encoding="utf-8",
        )
        java.chmod(0o755)
        (self.package / "bin").mkdir()
        (self.package / "bin/refactorkit").write_text("#!/bin/sh\n", encoding="utf-8")
        (self.package / "lib").mkdir()
        for jar in MODULE.REQUIRED_JARS:
            (self.package / "lib" / jar).write_bytes((jar + "\n").encode())
        self.smoke = self.root / "smoke.log"
        self.smoke.write_text(MODULE.SMOKE_MARKER + "\n", encoding="utf-8")
        projection = self.repository / "docs/releases/v0.7.0-kotlin-support.json"
        support = {
            "status": "PASSED",
            "failures": [],
            "supportProjectionSha256": hashlib.sha256(projection.read_bytes()).hexdigest(),
            "licenseRows": 10,
            "sbomState": "configured-unpublished",
        }
        self.support = self.root / "support.json"
        self.support.write_text(json.dumps(support), encoding="utf-8")
        self.revision = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=self.repository, check=True, text=True, capture_output=True,
        ).stdout.strip()
        self.output = self.root / "receipt.json"

    def tearDown(self) -> None:
        self.temp.cleanup()

    def run_finalizer(self, declared_platform: str | None = None):
        return subprocess.run([
            "python3", str(FINALIZER),
            "--platform", declared_platform or MODULE.detected_platform(),
            "--revision", self.revision,
            "--support-receipt", str(self.support),
            "--smoke-log", str(self.smoke),
            "--report-root", str(self.reports),
            "--package-root", str(self.package),
            "--repository-root", str(self.repository),
            "--support-projection", "docs/releases/v0.7.0-kotlin-support.json",
            "--output", str(self.output),
        ], cwd=ROOT, text=True, capture_output=True, check=False)

    def test_passing_inputs_create_bound_receipt(self) -> None:
        result = self.run_finalizer()
        self.assertEqual(0, result.returncode, result.stderr)
        receipt = json.loads(self.output.read_text())
        self.assertEqual("PASSED", receipt["status"])
        self.assertEqual(self.revision, receipt["revision"])
        self.assertEqual(MODULE.detected_platform(), receipt["detectedPlatform"])
        self.assertEqual(len(MODULE.REQUIRED_SUITES), receipt["junit"]["totals"]["executed"])
        self.assertEqual("configured-unpublished", receipt["support"]["sbomState"])

    def test_dirty_tracked_subject_fails(self) -> None:
        (self.repository / "subject.txt").write_text("dirty subject\n", encoding="utf-8")
        result = self.run_finalizer()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("tracked checkout is dirty", result.stderr)

    def test_missing_required_suite_fails(self) -> None:
        next((self.reports).glob("*/build/test-results/test/TEST-0.xml")).unlink()
        result = self.run_finalizer()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("required focused JUnit suites are missing", result.stderr)

    def test_unexpected_non_focused_suite_fails(self) -> None:
        report = self.reports / "focused/build/test-results/test/TEST-unexpected.xml"
        report.write_text(
            '<testsuite name="org.refactorkit.UnrelatedTest" tests="1" skipped="0" '
            'failures="0" errors="0"><testcase name="case"/></testsuite>\n',
            encoding="utf-8",
        )
        result = self.run_finalizer()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("unexpected non-focused JUnit reports", result.stderr)

    def test_failing_junit_fails(self) -> None:
        report = next(self.reports.glob("*/build/test-results/test/TEST-0.xml"))
        report.write_text(report.read_text().replace('failures="0"', 'failures="1"'))
        result = self.run_finalizer()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("JUnit aggregate is not passing", result.stderr)

    def test_support_hash_mismatch_fails(self) -> None:
        payload = json.loads(self.support.read_text())
        payload["supportProjectionSha256"] = "0" * 64
        self.support.write_text(json.dumps(payload))
        result = self.run_finalizer()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("support projection hash", result.stderr)

    def test_missing_smoke_marker_fails(self) -> None:
        self.smoke.write_text("not qualified\n")
        result = self.run_finalizer()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("passing marker", result.stderr)

    def test_declared_platform_mismatch_fails(self) -> None:
        wrong = next(candidate for candidate in sorted(MODULE.PLATFORMS)
                     if candidate != MODULE.detected_platform())
        result = self.run_finalizer(wrong)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("qualification platform mismatch", result.stderr)

    def test_wrong_embedded_runtime_fails(self) -> None:
        java = self.package / "runtime/bin/java"
        java.write_text(
            '#!/bin/sh\necho \'openjdk version "21.0.11"\' >&2\n'
            'echo \'OpenJDK Runtime Environment (build 21.0.11+11)\' >&2\n'
        )
        java.chmod(0o755)
        result = self.run_finalizer()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("not exact qualified JDK", result.stderr)


if __name__ == "__main__":
    unittest.main()
