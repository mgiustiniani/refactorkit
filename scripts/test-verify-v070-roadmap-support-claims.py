#!/usr/bin/env python3
"""Focused fail-closed tests for the v0.7.0 claim reconciler."""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


class RoadmapSupportClaimVerifierTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        releases = self.root / "docs/releases"
        releases.mkdir(parents=True)
        workflows = self.root / ".github/workflows"
        workflows.mkdir(parents=True)
        repository = Path(__file__).resolve().parents[1]
        for name in ("v0.7.0-plan.md", "v0.7.0-support-matrix.md"):
            (releases / name).write_text(
                (repository / "docs/releases" / name).read_text(encoding="utf-8"),
                encoding="utf-8",
            )
        (workflows / "ci.yml").write_text(
            (repository / ".github/workflows/ci.yml").read_text(encoding="utf-8"),
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _run(self) -> tuple[subprocess.CompletedProcess[str], dict[str, object]]:
        script = Path(__file__).with_name("verify-v070-roadmap-support-claims.py")
        result = subprocess.run(
            [sys.executable, str(script), "--repository-root", str(self.root)],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        return result, json.loads(result.stdout)

    def test_current_projection_passes(self) -> None:
        result, receipt = self._run()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("PASSED", receipt["status"])
        self.assertEqual(4, receipt["nativeRows"])
        self.assertEqual("PASSED", receipt["nativeEvidenceState"])
        self.assertFalse(receipt["nativeParentRowsOpen"])
        self.assertEqual(4, receipt["exactTemurin2111Pins"])
        self.assertEqual(7, len(receipt["closedRoadmapRowsVerified"]))

    def test_reopened_qualified_kotlin_foundation_row_fails(self) -> None:
        path = self.root / "docs/releases/v0.7.0-plan.md"
        text = path.read_text(encoding="utf-8").replace(
            "- [x] Publish qualified Kotlin/JDK/Gradle/Maven support rows and license/SBOM",
            "- [ ] Publish qualified Kotlin/JDK/Gradle/Maven support rows and license/SBOM",
        )
        path.write_text(text, encoding="utf-8")
        result, receipt = self._run()
        self.assertNotEqual(0, result.returncode)
        self.assertIn(
            "qualified roadmap row is not explicitly closed: kotlin-support",
            receipt["failures"],
        )

    def test_checked_roadmap_row_without_projection_update_fails(self) -> None:
        path = self.root / "docs/releases/v0.7.0-plan.md"
        text = path.read_text(encoding="utf-8").replace(
            "- [ ] Source-file relocation using exact `getEditsForFileRename` authority,",
            "- [x] Source-file relocation using exact `getEditsForFileRename` authority,",
        )
        path.write_text(text, encoding="utf-8")
        result, receipt = self._run()
        self.assertNotEqual(0, result.returncode)
        self.assertIn(
            "roadmap row is no longer explicitly open: typescript-advanced",
            receipt["failures"],
        )

    def test_passed_native_rows_with_open_parent_fail(self) -> None:
        path = self.root / "docs/releases/v0.7.0-plan.md"
        text = path.read_text(encoding="utf-8").replace(
            "- [x] Prove semantic preview, one-transaction apply, exact post-apply diagnostics,",
            "- [ ] Prove semantic preview, one-transaction apply, exact post-apply diagnostics,",
        )
        path.write_text(text, encoding="utf-8")
        result, receipt = self._run()
        self.assertNotEqual(0, result.returncode)
        self.assertIn(
            "native rows cannot be PASSED while either P0 native parent row is open",
            receipt["failures"],
        )

    def test_removed_nonclaim_fails(self) -> None:
        path = self.root / "docs/releases/v0.7.0-support-matrix.md"
        text = path.read_text(encoding="utf-8").replace(
            "All nine unchecked T5 advanced-operation rows remain unqualified.",
            "",
        )
        path.write_text(text, encoding="utf-8")
        result, receipt = self._run()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("support boundary is missing: typescript-boundary", receipt["failures"])

    def test_missing_dedicated_native_finalizer_fails(self) -> None:
        path = self.root / ".github/workflows/ci.yml"
        text = path.read_text(encoding="utf-8").replace(
            "scripts/finalize-native-maven-move-class-authority.py",
            "scripts/missing-native-finalizer.py",
        )
        path.write_text(text, encoding="utf-8")
        result, receipt = self._run()
        self.assertNotEqual(0, result.returncode)
        self.assertIn(
            "dedicated native workflow token is missing: revision-bound-finalizer",
            receipt["failures"],
        )

    def test_k1_native_checkout_must_bind_pull_request_source_head(self) -> None:
        path = self.root / ".github/workflows/ci.yml"
        text = path.read_text(encoding="utf-8")
        prefix, block = text.split("  k1-k2-shared-foundations-native:", 1)
        block = block.replace(
            "ref: ${{ github.event.pull_request.head.sha || github.sha }}",
            "ref: ${{ github.sha }}",
            1,
        )
        path.write_text(prefix + "  k1-k2-shared-foundations-native:" + block, encoding="utf-8")
        result, receipt = self._run()
        self.assertNotEqual(0, result.returncode)
        self.assertIn(
            "dedicated K1/K2 native workflow token is missing: exact-source-head-checkout",
            receipt["failures"],
        )

    def test_k1_native_receipt_must_bind_same_source_head(self) -> None:
        path = self.root / ".github/workflows/ci.yml"
        text = path.read_text(encoding="utf-8")
        prefix, block = text.split("  k1-k2-shared-foundations-native:", 1)
        block = block.replace(
            '"--revision", "${{ github.event.pull_request.head.sha || github.sha }}"',
            '"--revision", "${{ github.sha }}"',
            1,
        )
        path.write_text(prefix + "  k1-k2-shared-foundations-native:" + block, encoding="utf-8")
        result, receipt = self._run()
        self.assertNotEqual(0, result.returncode)
        self.assertIn(
            "dedicated K1/K2 native workflow token is missing: exact-source-head-receipt",
            receipt["failures"],
        )

    def test_unavailable_or_unpinned_jdk_fails(self) -> None:
        path = self.root / ".github/workflows/ci.yml"
        text = path.read_text(encoding="utf-8").replace(
            "java-version: '21.0.11+10.0.LTS'",
            "java-version: '21.0.11+9'",
            1,
        )
        path.write_text(text, encoding="utf-8")
        result, receipt = self._run()
        self.assertNotEqual(0, result.returncode)
        self.assertIn(
            "CI must pin build, dedicated authority, runtime, and K1/K2 foundation jobs to exactly four setup-java 21.0.11+10.0.LTS entries, found 3",
            receipt["failures"],
        )
        self.assertIn("CI contains unavailable setup-java version 21.0.11+9", receipt["failures"])


if __name__ == "__main__":
    unittest.main()
