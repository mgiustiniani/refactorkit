#!/usr/bin/env python3
"""Focused fail-closed tests for native Maven move-class receipt finalization."""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


class NativeMavenMoveClassFinalizerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.repository = self.root / "repository"
        self.build = self.repository / "modules/refactorkit-cli/build"
        self.output = self.build / "qualification/native-receipt.json"
        source = Path(__file__).resolve().parents[1]
        for relative in (
            "docs/requirements/req-java-maven-move-auth-013-baseline.md",
            "features/java-maven-move-class-apply-authority.feature",
        ):
            target = self.repository / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((source / relative).read_bytes())
        self._write_public_receipts()
        self._write_matrix(source)
        self._write_runtime()
        self._git("init", "-q")
        self._git("add", ".")
        self._git("-c", "user.name=fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "fixture")
        self.revision = self._git("rev-parse", "HEAD").stdout.strip()

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _git(self, *arguments: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["git", *arguments],
            cwd=self.repository,
            check=True,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )

    def _write_public_receipts(self) -> None:
        report = self.build / "reports/cucumber/packaged-maven-move-class-authority.json"
        report.parent.mkdir(parents=True)
        report.write_text(json.dumps([{
            "elements": [{
                "type": "scenario",
                "before": [{"result": {"status": "passed"}}],
                "steps": [{"result": {"status": "passed"}}],
                "after": [{"result": {"status": "passed"}}],
            }],
        }]), encoding="utf-8")
        junit = self.build / "test-results/packagedMavenMoveClassAuthorityTest"
        junit.mkdir(parents=True)
        cases = '<testcase name="selected"/>' + "".join(
            f'<testcase name="skipped-{index}"><skipped/></testcase>' for index in range(21)
        )
        (junit / "TEST-fixture.xml").write_text(
            f'<testsuite tests="22" skipped="21" failures="0" errors="0">{cases}</testsuite>',
            encoding="utf-8",
        )

    def _write_matrix(self, source: Path) -> None:
        matrix = self.build / "qualification/packaged-maven-move-class-authority/matrix-manifest.json"
        matrix.parent.mkdir(parents=True)
        matrix.write_text(json.dumps({
            "status": "PASSED",
            "totals": {"requirements": 13, "scenarios": 31, "steps": 436, "hooks": 62},
            "featureSha256": "3c42982c9d792c78162b680ff195fab361551f7392574f43c7247812bcb50696",
            "req013BaselineSha256": "dfab8db43830383aa771b0ecead5394e1525a7f16732dfc1f19acac8261878d6",
        }), encoding="utf-8")

    def _write_runtime(self) -> None:
        runtime = self.build / "package/refactorkit/runtime"
        binary = runtime / "bin" / ("java.exe" if os.name == "nt" else "java")
        binary.parent.mkdir(parents=True)
        if os.name == "nt":
            shutil.copy2(shutil.which("java") or self.fail("java executable unavailable"), binary)
        else:
            binary.write_text("#!/bin/sh\necho 'openjdk version \\\"21.0.11\\\"' >&2\n", encoding="utf-8")
            binary.chmod(0o755)
        (runtime / "release").write_text('JAVA_VERSION="21.0.11"\n', encoding="utf-8")

    def _run(self, revision: str | None = None) -> tuple[subprocess.CompletedProcess[str], dict[str, object]]:
        script = Path(__file__).with_name("finalize-native-maven-move-class-authority.py")
        result = subprocess.run(
            [
                sys.executable,
                str(script),
                "--repository-root", str(self.repository),
                "--build-root", str(self.build),
                "--platform", "linux-x86_64",
                "--expected-revision", revision or self.revision,
                "--output", str(self.output),
            ],
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env={**os.environ, "GITHUB_RUN_ID": "fixture-run"},
        )
        return result, json.loads(self.output.read_text(encoding="utf-8"))

    def test_complete_revision_bound_receipt_passes(self) -> None:
        result, receipt = self._run()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("PASSED", receipt["status"])
        self.assertEqual(self.revision, receipt["sourceRevision"])
        self.assertEqual(31, receipt["completeMatrix"]["totals"]["scenarios"])

    def test_revision_mismatch_writes_failed_receipt(self) -> None:
        result, receipt = self._run("0" * 40)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("FAILED", receipt["status"])
        self.assertIn("revision mismatch", receipt["failure"])

    def test_failed_matrix_writes_failed_receipt(self) -> None:
        path = self.build / "qualification/packaged-maven-move-class-authority/matrix-manifest.json"
        document = json.loads(path.read_text(encoding="utf-8"))
        document["status"] = "FAILED"
        path.write_text(json.dumps(document), encoding="utf-8")
        self._git("add", str(path.relative_to(self.repository)))
        self._git("-c", "user.name=fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "failed matrix")
        self.revision = self._git("rev-parse", "HEAD").stdout.strip()
        result, receipt = self._run()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("FAILED", receipt["status"])
        self.assertIn("complete packaged matrix", receipt["failure"])


if __name__ == "__main__":
    unittest.main()
